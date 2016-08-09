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
import CodecMap from './codec_map';


var streams = {};

var pendingUpdates = [];

function _addPendingUpdate(alias) {
  if (_.indexOf(pendingUpdates, alias) < 0) pendingUpdates.push(alias);
}

function _handleDictionaryMapState(alias, data) {
  var seed = data.seed;
  var seq = data.seq;
  var values = data.values;
  var dictionary = data.dictionary;

  // console.info(`!>>>> Data arived (map) for ${alias}, data = ${values}`);

  streams[alias] = {
    seed: seed,
    seq: seq,
    dict: dictionary,
    values: values,
    cached: false
  };

  _addPendingUpdate(alias);
}

function _handleDictionaryMapDiffs(alias, data) {

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

  if (!streamData.dict) {
    Streams.invalidateAlias(alias);
    delete streams[alias];
    return;
  }

  var values = streamData.values;
  for (var i = 0; i < values.length; i++) {
    var nextDiff = diffs[i];
    if (CodecMap.hasChange(nextDiff)) {
      values[i] = nextDiff;
    }
  }

  streamData.seq = seq2;
  streamData.cached = false;
  _addPendingUpdate(alias);


}


function _getDataFor(alias) {
  var streamData = streams[alias];
  if (!streamData) return false;
  if (!streamData.cached) {
    var newStruct = {};
    for (var i = 0; i < streamData.dict.length; i++) {
      newStruct[streamData.dict[i]] = streamData.values[i];
    }
    streamData.cached = newStruct;
  }
  return streamData.cached;
}

function _updateFor(alias) {
  var data = _getDataFor(alias);
  if (data) {
    // console.debug(`!>>>> have data for ${alias} : ${JSON.stringify(data)}`);
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

CodecMap.signals.snapshotUpdateReceived.add(_handleDictionaryMapState);
CodecMap.signals.partialUpdateReceived.add(_handleDictionaryMapDiffs);

Streams.signals.lastUpdatePosted.add(_processPendingUpdates);
Streams.signals.dataDiscarded.add(_discardAlias);
Streams.signals.allDataDiscarded.add(_discardAllData);
Streams.signals.dataRequested.add(_updateFor);




