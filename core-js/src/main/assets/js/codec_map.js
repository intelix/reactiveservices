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


define(['lodash', './logging', 'signals', './codec_base'], function (_, Log, Signal, Codec) {

    var TypeDictionaryMapStreamState = 52;
    var TypeDictionaryMapNoChange = 53;
    var TypeDictionaryMapStreamTransitionPartial = 54;

    var signals = {
        snapshotUpdateReceived: new Signal(),
        partialUpdateReceived: new Signal()
    };

    Codec.addDecoder(TypeDictionaryMapStreamState, function (frame) {
        return {
            data: {
                seed: Codec.readInt(frame),
                seq: Codec.readInt(frame),
                values: Codec.readArrayOfAny(frame),
                dictionary: Codec.readArrayOfString(frame)
            },
            dispatcher: signals.snapshotUpdateReceived
        };
    });
    Codec.addDecoder(TypeDictionaryMapNoChange, function (frame) {
        return {
            _no_change: true
        };
    });
    Codec.addDecoder(TypeDictionaryMapStreamTransitionPartial, function (frame) {
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


    return {
        signals: signals,
        hasChange: function (value) {
            return !value || !value._no_change;
        }
    };

});
