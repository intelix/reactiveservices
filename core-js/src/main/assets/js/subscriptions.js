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

define(['lodash',
        './logging',
        'signals',
        './socket',
        './codec_base',
        './handlers'
    ],

    function (_,
              Log,
              Signal,
              Socket,
              Codec,
              Streams) {

        var componentId = "core/Subscriptions";

        var correlationIdCounter = 0;

        var listeners = [];
        var aliases = [];
        var subjectToAliasMap = {};

        var pendingDataRequests = [];
        var pendingDataRequestsTimer = false;

        var subscriptionMaintenanceTimer = false;

        var globalSubscribers = {};
        var serviceUnavailableNotificationSubscribers = {};
        var subscriptionsForHousekeeping = {};

        function _onDisconnected() {
            globalSubscribers = {};
            subscriptionsForHousekeeping = {};
            aliases = [];
            subjectToAliasMap = {};
            serviceUnavailableNotificationSubscribers = {};
        }

        function _nextCorrelationId() {
            correlationIdCounter += 1;
            return correlationIdCounter.toString(32);
        }

        function _keyFromAlias(alias) {
            if (aliases.length > alias && alias >= 0) return aliases[alias];
            return false;
        }

        function _aliasFor(service, topic, createNewIfNotFound) {
            var serviceCollection = subjectToAliasMap[service];
            if (_.isUndefined(serviceCollection)) {
                serviceCollection = {};
                subjectToAliasMap[service] = serviceCollection;
            }
            var alias = serviceCollection[topic];
            if (_.isUndefined(alias)) {
                if (createNewIfNotFound) {
                    alias = aliases.length;
                    var key = service + "|" + topic;
                    aliases.push(key);
                    serviceCollection[topic] = alias;
                    Codec.newAlias(alias, service, topic);
                } else {
                    alias = -1;
                }
            }
            return alias;
        }

        function _requestDataForAlias(alias) {
            Streams.signals.dataRequested.dispatch(alias);
        }

        function _processPendingDataRequests() {
            pendingDataRequestsTimer = false;
            var list = pendingDataRequests;
            pendingDataRequests = [];
            list.forEach(_requestDataForAlias);
        }

        function _subscribeToServiceUnavailableNotifications(service, callback) {
            var subscribers = serviceUnavailableNotificationSubscribers[service];
            if (!subscribers) subscribers = [];
            subscribers.push(callback);
            serviceUnavailableNotificationSubscribers[service] = subscribers;
        }

        function _unsubscribeToServiceUnavailableNotifications(service, callback) {
            var subscribers = serviceUnavailableNotificationSubscribers[service];
            if (!subscribers) subscribers = [];
            subscribers = subscribers.filter(function (next) {
                return next != callback;
            });
            serviceUnavailableNotificationSubscribers[service] = subscribers;
        }

        function _subscribeToSharedStream(service, topic, priority, throttling, callbacks) {
            var key = service + "|" + topic;
            var alias = _aliasFor(service, topic, true);
            var subscribers = globalSubscribers[key];
            if (!subscribers) {
                subscribers = [];
                if (Log.isDebug()) Log.logDebug(componentId, "Server subscription for: " + key);
                Codec.subscribe(alias, priority, throttling);
            } else {
                pendingDataRequests.push(alias);
                if (!pendingDataRequestsTimer) {
                    pendingDataRequestsTimer = setTimeout(_processPendingDataRequests, 1);
                }
            }
            if ($.inArray(callbacks, subscribers) < 0) {
                subscribers.push(callbacks);
                globalSubscribers[key] = subscribers;
                if (Log.isDebug()) Log.logDebug(componentId, "New interest for: " + key + ", total listeners: " + subscribers.length);
            }

        }


        function _unsubscribeFromSharedStream(service, topic, callbacks) {
            var key = service + "|" + topic;
            var subscribers = globalSubscribers[key];
            if (!subscribers) {
                return;
            }
            subscribers = subscribers.filter(function (el) {
                return el != callbacks;
            });

            if (Log.isDebug()) Log.logDebug(componentId, "Removed listener for: " + key + ", remaining " + subscribers.length);

            if (!subscribers || subscribers.length === 0) {

                if (Log.isDebug()) Log.logDebug(componentId, "Scheduled for server unsubscribe: " + key);

                subscriptionsForHousekeeping[key] = {
                    ts: Date.now(),
                    service: service,
                    topic: topic
                };
                if (!subscriptionMaintenanceTimer) {
                    subscriptionMaintenanceTimer = setTimeout(_subscriptionMaintenance, 30 * 1000);
                }
            }
            globalSubscribers[key] = subscribers;
        }


        function _onStreamUpdate(alias, data) {
            var subjectKey = _keyFromAlias(alias);
            if (subjectKey) {
                var subscribers = globalSubscribers[subjectKey];

                if (subscribers && subscribers.length > 0) {
                    subscribers.forEach(function (next) {
                        next.onUpdate(data);
                    });
                }
            } else {
                Log.logError(componentId, "Unrecognised alias: " + alias);
            }
        }

        function _onInvalidRequest(alias) {
            var subjectKey = _keyFromAlias(alias);
            if (Log.isDebug()) Log.logDebug(componentId, "Invalid subject: " + subjectKey);
            if (subjectKey) {
                var subscribers = globalSubscribers[subjectKey];
                if (subscribers && subscribers.length > 0) {
                    subscribers.forEach(function (next) {
                        next.onError();
                    });
                }
            }
        }

        function _onServiceUnavailable(service) {
            var subscribers = serviceUnavailableNotificationSubscribers[service];

            if (subscribers && subscribers.length > 0) {
                subscribers.forEach(function (next) {
                    next();
                });
            }
        }


        function _subscriptionMaintenance() {
            if (Log.isDebug()) Log.logDebug(componentId, "Subscription maintenance...");
            var reschedule = false;
            subscriptionMaintenanceTimer = false;
            if (subscriptionsForHousekeeping) {
                var threshold = Date.now() - 15 * 1000;
                var remainder = {};
                for (var key in subscriptionsForHousekeeping) {
                    var el = subscriptionsForHousekeeping[key];
                    var unsubTime = el.ts;
                    if (unsubTime < threshold) {
                        var subscribers = globalSubscribers[key];
                        if (!subscribers || subscribers.length === 0) {
                            if (Log.isDebug()) Log.logDebug(componentId, "Unsubscribing from server: " + el.service + "#" + el.topic);

                            var alias = _aliasFor(el.service, el.topic, false);
                            if (!_.isUndefined(alias) && alias > -1) {
                                Codec.unsubscribe(alias);
                                Streams.signals.dataDiscarded.dispatch(alias);
                            }

                            delete globalSubscribers[key];


                        } else {
                            if (Log.isDebug()) Log.logDebug(componentId, "Subscription " + key + " is live again and no longer queued for removal");
                        }
                    } else {
                        reschedule = true;
                        remainder[key] = el;
                    }
                }
                subscriptionsForHousekeeping = remainder;
            }
            if (reschedule) {
                subscriptionMaintenanceTimer = setTimeout(_subscriptionMaintenance, 30 * 1000);
            }
        }


        Socket.signals.disconnected.add(_onDisconnected);
        Streams.signals.streamUpdate.add(_onStreamUpdate);
        Streams.signals.serviceUnavailable.add(_onServiceUnavailable);
        Streams.signals.invalidRequest.add(_onInvalidRequest);

        return {

            createHandle: function () {

                var localSubscriptions = [];
                var signalHandles = {};

                function _removeLocalSubscription(service, topic) {
                    localSubscriptions = localSubscriptions.filter(function (next) {
                        var isDifferent = next.service != service || next.topic != topic;
                        if (!isDifferent) {
                            _unsubscribeToServiceUnavailableNotifications(next.service, next.serviceUnavailableCallback);
                            _unsubscribeFromSharedStream(next.service, next.topic, next.callbacks);
                        }
                        return isDifferent;
                    });
                }

                var handle = {
                    terminate: function () {
                        localSubscriptions.forEach(function (next) {
                            _unsubscribeFromSharedStream(next.service, next.topic, next.callbacks);
                        });
                        localSubscriptions = [];
                        listeners = listeners.filter(function (next) {
                            return next != handle;
                        });
                    },
                    subscribe: function (service, topic, priority, throttling, callbacks, serviceUnavailableCallback) {
                        _removeLocalSubscription(service, topic);

                        localSubscriptions.push({
                            service: service,
                            topic: topic,
                            callbacks: callbacks,
                            serviceUnavailableCallback: serviceUnavailableCallback
                        });
                        _subscribeToSharedStream(service, topic, priority, throttling, callbacks);
                        if (serviceUnavailableCallback && _.isFunction(serviceUnavailableCallback))
                            _subscribeToServiceUnavailableNotifications(service, serviceUnavailableCallback);
                    },
                    unsubscribe: function (service, topic) {
                        _removeLocalSubscription(service, topic);
                    },
                    signal: function (service, topic, data, orderingGroup, expireInSeconds, onSuccess, onFailure) {
                        var alias = _aliasFor(service, topic, true);
                        var expireInSecondsValue = _.isUndefined(expireInSeconds) || !_.isNumber(expireInSeconds) ? 60 : expireInSeconds;
                        var correlationId = false;

                        var handler = false;
                        var timer = false;

                        function _timeout() {
                            if (handler) {
                                handler(alias, correlationId, false, {error: "timeout"});
                            }
                        }

                        function _removeHandler() {
                            if (handler) {
                                Codec.signals.signalResult.remove(handler);
                                if (timer) clearTimeout(timer);
                                timer = false;
                                handler = false;
                            }
                        }

                        if (_.isFunction(onSuccess) || _.isFunction(onFailure)) {

                            correlationId = _nextCorrelationId();

                            handler = function (alias, correlation, result, payload) {
                                if (correlation == correlationId) {
                                    if (!result && _.isFunction(onFailure)) onFailure(payload);
                                    else if (result && _.isFunction(onSuccess)) onSuccess(payload);
                                    _removeHandler();
                                }
                            };
                            Codec.signals.signalResult.add(handler);

                            timer = setTimeout(_timeout, (expireInSecondsValue + 1) * 1000);
                        }


                        Codec.signal(alias, data, orderingGroup, expireInSecondsValue, correlationId);
                    }
                };
                listeners.push(handle);
                return handle;

            }
        };

    });