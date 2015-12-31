/*
 * Copyright 2014-16 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

define(['./logging', './handlers', './codec_list'], function (Log, Streams, CodecList) {

    var streams = {};

    var pendingUpdates = [];

    function _addPendingUpdate(alias) {
        if (_.indexOf(pendingUpdates, alias) < 0) pendingUpdates.push(alias);
    }

    function _handleSnapshot(alias, data) {
        var seed = data.seed;
        var seq = data.seq;
        var values = data.values;
        var max = data.max;
        var eviction = data.eviction;

        streams[alias] = {
            seed: seed,
            seq: seq,
            max: max,
            eviction: eviction,
            values: values
        };

        _addPendingUpdate(alias);
    }

    function _handlePartials(alias, data) {

        var seed = data.seed;
        var seq = data.seq;
        var seq2 = data.seq2;
        var diffs = data.diffs;

        var streamData = streams[alias];
        if (!streamData) {
            Streams.invalidateAlias(alias);
            return;
        }

        if (seed != streamData.seed) {
            Streams.invalidateAlias(alias);
            delete streams[alias];
            return;
        }

        if (seq != streamData.seq) {
            Streams.invalidateAlias(alias);
            delete streams[alias];
            return;
        }

        var max = streamData.max;
        var eviction = streamData.eviction;

        var list = streamData.values;
        if (!list || !_.isArray(list)) list = [];

        for (var i = 0; i < diffs.length; i++) {
            var nextDiff = diffs[i];

            if (max > 0 && eviction === 0 && list.length >= max) {
                return;
            }

            var pos = nextDiff.position;
            if (CodecList.isRemove(nextDiff)) {
                list.splice(pos, 1);
            } else if (CodecList.isAdd(nextDiff)) {
                if (pos < 0) {
                    pos = list.length + pos + 1;
                }
                list.splice(pos, 0, nextDiff.value);
            } else if (CodecList.isReplace(nextDiff)) {
                if (pos < 0) {
                    pos = list.length + pos;
                }
                list.splice(pos, 1, nextDiff.value);
            }
            if (max > 0 && list.length > max) {
                if (eviction === 1) list.splice(0, 1);
                if (eviction === 2) list.splice(-1, 1);
            }

        }

        streamData.seq = seq2;
        streamData.values = list;
        _addPendingUpdate(alias);

    }


    function _getDataFor(alias) {
        var streamData = streams[alias];
        if (!streamData) return false;
        return streamData.values;
    }

    function _updateFor(alias) {
        var data = _getDataFor(alias);
        if (data) {
            Streams.updateForAlias(alias, data);
        }
    }

    function _processPendingUpdates() {
        if (pendingUpdates.length < 1) return;
        pendingUpdates.forEach(_updateFor);
        pendingUpdates = [];
    }

    function _discardAlias(alias) {
        delete streams[alias];
    }

    function _discardAllData() {
        streams = {};
        pendingUpdates = [];
    }

    CodecList.signals.snapshotUpdateReceived.add(_handleSnapshot);
    CodecList.signals.partialUpdateReceived.add(_handlePartials);

    Streams.signals.lastUpdatePosted.add(_processPendingUpdates);
    Streams.signals.dataDiscarded.add(_discardAlias);
    Streams.signals.allDataDiscarded.add(_discardAllData);
    Streams.signals.dataRequested.add(_updateFor);


});
