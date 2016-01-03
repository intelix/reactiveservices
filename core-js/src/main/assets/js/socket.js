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

define(['module', './logging', 'signals'], function (Module, Log, Signal) {

    var signals = {
        connected: new Signal(),
        connectionCommenced: new Signal(),
        disconnected: new Signal(),
        receivedFrame: new Signal()
    };


    var componentId = "core/Websocket";

    var endpoints = _.isUndefined(Module.config().endpoints) ? [] : Module.config().endpoints;

    var endpointToTryIdx = 0;
    var endpointFailureCount = 0;

    var reconnectInterval = _.isUndefined(Module.config().reconnectInterval) ? 100 : Module.config().reconnectInterval;
    var connectionEstablishTimeout = _.isUndefined(Module.config().connectionEstablishTimeout) ? 4000 : Module.config().connectionEstablishTimeout;

    var currentState = WebSocket.CLOSED;

    var attemptsSoFar = 0;
    var forcedClose = false;

    var connectedToEndpoint = null;

    var socket;

    var reconnectTimer = false;

    function _connected() {
        return currentState == WebSocket.OPEN;
    }

    function _nextEndpoint() {
        if (endpoints.length === 0) {
            Log.logError(componentId, "Unable to establish WebSocket connection - no endpoints provided");
            return null;
        }
        if (endpointFailureCount > 1) {
            endpointToTryIdx++;
        }
        if (endpointToTryIdx >= endpoints.length) {
            endpointToTryIdx = 0;
        }
        return endpoints[endpointToTryIdx];
    }

    function _attemptConnection() {
        forcedClose = false;

        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = false;
        }

        var endpoint = _nextEndpoint();
        if (!endpoint) {
            return;
        }

        signals.connectionCommenced.dispatch();

        attemptsSoFar++;
        currentState = WebSocket.CONNECTING;


        socket = new WebSocket(endpoint);
        socket.binaryType = 'arraybuffer';

        if (Log.isInfo()) Log.logInfo(componentId, "Connection attempt to " + endpoint);

        var timeout = setTimeout(function () {
            if (socket) socket.close();
        }, connectionEstablishTimeout);

        socket.onopen = function (x) {

            clearTimeout(timeout);
            currentState = WebSocket.OPEN;
            attemptsSoFar = 0;
            connection = socket;
            connectedToEndpoint = endpoint;

            signals.connected.dispatch(endpoint);

            endpointFailureCount = 0;
            if (Log.isInfo()) Log.logInfo(componentId, "WebSocket connected to " + endpoint + " after " + attemptsSoFar + " attempt(s)");
        };

        socket.onclose = function (x) {
            clearTimeout(timeout);
            socket = false;
            connectedToEndpoint = false;
            signals.disconnected.dispatch({wasOpen: (currentState == WebSocket.OPEN )});
            currentState = WebSocket.CLOSED;
            if (!forcedClose) {
                endpointFailureCount++;
                reconnectTimer = setTimeout(function () {
                    reconnectTimer = false;
                    _attemptConnection();
                }, reconnectInterval);
            }
        };

        socket.onmessage = function (e) {
            signals.receivedFrame.dispatch(e.data);
        };
    }

    function _initiateConnect(ep) {
        if (!_.isUndefined(ep)) endpoints = _.isArray(ep) ? ep : [ep];
        endpointToTryIdx = 0;
        endpointFailureCount = 0;
        if (!_.isUndefined(socket) && !_.isUndefined(socket.close) ) {
            socket.close();
        } else if (!reconnectTimer) _attemptConnection();
    }

    function _send(msg) {
        if (_connected()) {
            socket.send(msg);
        } else {
            if (Log.isDebug()) Log.logDebug(componentId, "Connection is not active, message dropped: " + msg);
        }
    }

    return {
        signals: signals,
        connect: _initiateConnect,
        sendFrame: _send,
        isConnected: _connected
    };

});