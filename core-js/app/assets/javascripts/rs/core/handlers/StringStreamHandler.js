/*
 * Copyright 2014-15 Intelix Pty Ltd
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

define(['logging', 'streams', 'codec_string'], function (Log, Streams, CodecString) {

    var streams = {};

    var pendingUpdates = [];

    function _addPendingUpdate(alias) {
        if (_.indexOf(pendingUpdates, alias) < 0) pendingUpdates.push(alias);
    }

    function _handleUpdate(alias, data) {

        streams[alias] = data;

        _addPendingUpdate(alias);
    }

    function _getDataFor(alias) {
        var streamData = streams[alias];
        if (!streamData) return false;
        return streamData;
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

    CodecString.signals.updateReceived.add(_handleUpdate);

    Streams.signals.lastUpdatePosted.add(_processPendingUpdates);
    Streams.signals.dataDiscarded.add(_discardAlias);
    Streams.signals.allDataDiscarded.add(_discardAllData);
    Streams.signals.dataRequested.add(_updateFor);


});
