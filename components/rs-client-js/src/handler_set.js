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

import * as _ from 'lodash';
import Log from './logging';
import Streams from './handlers';
import CodecSet from './codec_set';


var streams = {};

var pendingUpdates = [];

function _addPendingUpdate(alias) {
  if (_.indexOf(pendingUpdates, alias) < 0) pendingUpdates.push(alias);
}

function _handleSnapshot(alias, data) {
  var seed = data.seed;
  var seq = data.seq;
  var values = data.values;

  if (!values || !_.isArray(values)) values = [];

  streams[alias] = {
    seed: seed,
    seq: seq,
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

  var list = streamData.values;
  if (!list || !_.isArray(list)) list = [];

  function _handle(nextDiff) {
    var val = nextDiff.value;
    if (CodecSet.isRemove(nextDiff)) {
      list = list.filter(function (next) {
        return next != val;
      });
    } else {
      if ($.inArray(val, list) < 0) list.push(val);
    }
  }

  for (var i = 0; i < diffs.length; i++) {
    _handle(diffs[i]);
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

CodecSet.signals.snapshotUpdateReceived.add(_handleSnapshot);
CodecSet.signals.partialUpdateReceived.add(_handlePartials);

Streams.signals.lastUpdatePosted.add(_processPendingUpdates);
Streams.signals.dataDiscarded.add(_discardAlias);
Streams.signals.allDataDiscarded.add(_discardAllData);
Streams.signals.dataRequested.add(_updateFor);

