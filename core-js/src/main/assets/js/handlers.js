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

    var signals = {
        dataRequested: new Signal(),
        dataDiscarded: new Signal(),
        allDataDiscarded: new Signal(),
        lastUpdatePosted: new Signal(),

        streamUpdate: new Signal(),
        serviceUnavailable: new Signal(),
        invalidRequest: new Signal()
    };


    Codec.signals.streamUpdateReceived.add(function (alias, data) {
        if (!_.isUndefined(data.dispatcher) && !_.isUndefined(data.data)) {
            data.dispatcher.dispatch(alias, data.data);
        }
    });

    Codec.signals.frameProcessed.add(function () {
        signals.lastUpdatePosted.dispatch();
    });

    Codec.signals.serviceNotAvailableReceived.add(function (service) {
        signals.serviceUnavailable.dispatch(service);
    });

    Codec.signals.invalidRequestReceived.add(function (service) {
        signals.invalidRequest.dispatch(service);
    });


    function _invalidateAlias(alias) {
        Codec.reset(alias);
    }

    function _updateForAlias(alias, data) {
        signals.streamUpdate.dispatch(alias, data);
    }

    return {
        signals: signals,
        invalidateAlias: _invalidateAlias,
        updateForAlias: _updateForAlias
    };

});