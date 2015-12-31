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



define(['./logging', 'signals', './codec_base'], function (Log, Signal, Codec) {

    var TypeListStreamState = 59;
    var TypeListStreamTransitionPartial = 60;
    var TypeListAddOp = 61;
    var TypeListRemoveOp = 62;
    var TypeListReplaceOp = 63;

    var signals = {
        snapshotUpdateReceived: new Signal(),
        partialUpdateReceived: new Signal()
    };

    Codec.addDecoder(TypeListStreamState, function (frame) {
        return {
            data: {
                seed: Codec.readInt(frame),
                seq: Codec.readInt(frame),
                values: Codec.readArrayOfString(frame),
                max: Codec.readShort(frame),
                eviction: Codec.readByte(frame)
            },
            dispatcher: signals.snapshotUpdateReceived
        };
    });
    Codec.addDecoder(TypeListStreamTransitionPartial, function (frame) {
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
    Codec.addDecoder(TypeListAddOp, function (frame) {
        return {
            type: TypeListAddOp,
            position: Codec.readShort(frame),
            value: Codec.readString(frame)
        };
    });
    Codec.addDecoder(TypeListRemoveOp, function (frame) {
        return {
            type: TypeListRemoveOp,
            position: Codec.readShort(frame)
        };
    });
    Codec.addDecoder(TypeListReplaceOp, function (frame) {
        return {
            type: TypeListReplaceOp,
            position: Codec.readShort(frame),
            value: Codec.readString(frame)
        };
    });


    return {
        signals: signals,
        isAdd: function(value) { return !value || value.type === TypeListAddOp; },
        isRemove: function(value) { return !value || value.type === TypeListRemoveOp; },
        isReplace: function(value) { return !value || value.type === TypeListReplaceOp; }
    };

});
