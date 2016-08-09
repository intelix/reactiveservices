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
import Signal from 'signals';
import Codec from './codec_base';


var TypeSetStreamState = 55;
var TypeSetStreamTransitionPartial = 56;
var TypeSetAddOp = 57;
var TypeSetRemoveOp = 58;

var signals = {
  snapshotUpdateReceived: new Signal(),
  partialUpdateReceived: new Signal()
};

Codec.addDecoder(TypeSetStreamState, function (frame) {
  var result = {
    data: {
      seed: Codec.readInt(frame),
      seq: Codec.readInt(frame),
      values: Codec.readArrayOfAny(frame)
    },
    dispatcher: signals.snapshotUpdateReceived
  };
  Codec.readBoolean(frame); // skip; irrelevant for the client
  return result;
});
Codec.addDecoder(TypeSetStreamTransitionPartial, function (frame) {
  return {
    data: {
      seed: Codec.readInt(frame),
      seq: Codec.readInt(frame),
      seq2: Codec.readInt(frame),
      diffs: Codec.readArrayOfAny(frame)
    },
    dispatcher: signals.partialUpdateReceived
  };
});
Codec.addDecoder(TypeSetAddOp, function (frame) {
  return {
    type: TypeSetAddOp,
    value: Codec.readNextIfAny(frame)
  };
});
Codec.addDecoder(TypeSetRemoveOp, function (frame) {
  return {
    type: TypeSetRemoveOp,
    value: Codec.readNextIfAny(frame)
  };
});


export default {
  signals: signals,
  isAdd: function (value) {
    return !value || value.type === TypeSetAddOp;
  },
  isRemove: function (value) {
    return !value || value.type === TypeSetRemoveOp;
  }
}
