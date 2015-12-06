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

define(['react', 'logging', 'appevents', 'socket', 'subscriptions', 'auth'],
    function (React, Log, AppEvents, Socket, Subscriptions, Auth) {

        function _subId2String(config) {
            return config ? config.service + "#" + config.topic : "Nil";
        }

        return {

            componentNamePrefix: function () {
                return this.componentName ? this.componentName() : "";
            },

            /* Logging */

            isDebug: Log.isDebug,
            isInfo: Log.isInfo,
            isWarn: Log.isWarn,

            logDebug: function (msg) {
                Log.logDebug(this.componentNamePrefix(), msg);
            },
            logInfo: function (msg) {
                Log.logInfo(this.componentNamePrefix(), msg);
            },
            logWarn: function (msg) {
                Log.logWarn(this.componentNamePrefix(), msg);
            },
            logError: function (msg) {
                Log.logError(this.componentNamePrefix(), msg);
            },

            /* eventing */

            // TODO review all these, replace with signals
            //addEventListener: eventing.addEventListener,
            //removeEventListener: eventing.removeEventListener,
            //raiseEvent: eventing.raiseEvent,
            //bindToRaise: eventing.bindToRaise,

            /* permissions */

            hasSubjectPermission: Auth.hasSubjectPermission,
            hasDomainPermission: Auth.hasDomainPermission,

            /* misc */

            cx: function (v) {
                var cx = React.addons.classSet;
                return cx(v);
            },

            sendSignal: function (service, topic, data, orderingGroup, expireInSeconds, onSuccess, onFailure) {
                if (_.isObject(this.handle) && this.handle.signal) this.handle.signal(service, topic, data, orderingGroup, expireInSeconds, onSuccess, onFailure);
            },

            /* ReactJS hooks */

            componentWillMount: function () {
                var self = this;
                this.setState({connected: false});
                if (self.subscribeToEvents) {
                    var eventslist = self.subscribeToEvents();
                    eventslist.forEach(function (el) {
                        // TODO some basic validation and error reporting here
                        el[0].add(el[1]);
                    });
                }
            },

            componentDidMount: function () {
                var self = this;
                self.componentMounted = true;
                self._addSubscriptionConfigBuilder(self.subscriptionConfig);

                this.handle = Subscriptions.createHandle();

                function _onConnected() {
                    if (!self._visibilityMonitorEnabled() || (self.state && (self.state.visibility === true))) {
                        if (self.isDebug()) {
                            self.logDebug("Received ws connected event, re-subscribing");
                        }
                        self._reopenAllSubscriptions();
                    }
                    if (!self.state || !self.state.connected) {
                        if (self.componentMounted) self.setState({connected: true});
                        if (self.onConnected) self.onConnected();
                    }
                }

                function _onDisconnected() {
                    if (self.isDebug()) {
                        self.logDebug("Received ws disconnected event");
                    }
                    self._closeAllSubscriptions();
                    if (self.state && self.state.connected) {
                        if (self.componentMounted) self.setState({connected: false});
                        if (self.onDisconnected) self.onDisconnected();
                    }
                }

                // TODO remove listeners when component removed
                Socket.signals.connected.add(_onConnected);
                Socket.signals.disconnected.add(_onDisconnected);

                if (Socket.isConnected()) {
                    _onConnected();
                } else {
                    _onDisconnected();
                }

                if (self._visibilityMonitorEnabled()) {
                    AppEvents.signals.ElementVisibilityChanged.add(self._checkVisibility);
                    self._checkVisibility();
                }

                AppEvents.signals.CoreDataChanged.add(self._refresh);

                function _scheduleVisibilityCheck() {
                    self.visibilityCheckTimeout = setTimeout(_performVisibilityCheck, 500);
                }

                function _performVisibilityCheck() {
                    self._checkVisibility();
                    _scheduleVisibilityCheck();
                }

                _scheduleVisibilityCheck();

            },


            componentDidUpdate: function () {
                this._checkVisibility();
            },

            componentWillUnmount: function () {
                var self = this;
                self.componentMounted = false;
                self.configBuilders = [];

                if (self.visibilityCheckTimeout) {
                    clearTimeout(self.visibilityCheckTimeout);
                    self.visibilityCheckTimeout = false;
                }

                if (self.handle) {
                    self._closeAllSubscriptions();

                    self.handle.terminate();
                    self.handle = null;
                }
                if (self.subscribeToEvents) {
                    var eventslist = self.subscribeToEvents();
                    eventslist.forEach(function (el) {
                        el[0].remove(el[1]);
                    });
                }

                if (self._visibilityMonitorEnabled()) {
                    AppEvents.signals.ElementVisibilityChanged.remove(self._checkVisibility);
                }

                AppEvents.signals.CoreDataChanged.remove(self._refresh);

            },


            componentWillReceiveProps: function (nextProps) {
                var self = this;

                function different(id1, id2) {
                    return id1.service != id2.service ||
                        id1.topic != id2.topic;
                }

                if (self.handle && (self.subscribed)) {
                    var configs = self._getSubscriptionConfigsFor(self.props);
                    var newConfigs = self._getSubscriptionConfigsFor(nextProps);

                    var toClose = configs.filter(function (config) {
                        return !newConfigs.some(function (newConfig) {
                            return !different(config, newConfig);
                        });
                    });
                    var toOpen = newConfigs.filter(function (config) {
                        return !configs.some(function (newConfig) {
                            return !different(config, newConfig);
                        });
                    });

                    toClose.forEach(self._unsubscribeFromStream);
                    toOpen.forEach(self._subscribeToStream);

                }
            },

            /* internal */

            _addSubscriptionConfigBuilder: function (configBuilderFunc) {
                var a = this._getSubscriptionConfigBuilders();
                a.push(configBuilderFunc);
                this.subscriptionConfigBuilders = a;
            },

            _getSubscriptionConfigBuilders: function () {
                return _.isArray(this.subscriptionConfigBuilders) ? this.subscriptionConfigBuilders : [];
            },
            _getSubscriptionConfigsFor: function (props) {
                return _.flatten(this._getSubscriptionConfigBuilders().map(function (next) {
                    var arr = next ? next(props) : [];
                    return _.isArray(arr) ? arr : [];
                }));
            },


            _unsubscribeFromStream: function (config) {
                var self = this;

                if (self.handle && _.isFunction(self.handle.unsubscribe)) {
                    self.handle.unsubscribe(config.service, config.topic);
                }
                if (self.isDebug()) {
                    self.logDebug("Closed subscription for " + _subId2String(config)); // TODO should say 'dropped interest' or something like that
                }
            },


            _subscribeToStream: function (config) {
                var self = this;

                var onUpdate = (!_.isUndefined(config.onUpdate) && _.isFunction(config.onUpdate)) ? config.onUpdate : false;
                var onUnavailable = config.onUnavailable;
                var stateKey = config.stateKey;

                var callback = function (data) {
                    if (self.componentMounted) {
                        if (stateKey) {
                            var partialStateUpdate = {};
                            var existing = self.state[stateKey];
                            partialStateUpdate[stateKey] = data;
                            self.setState(partialStateUpdate);
                        }
                        if (onUpdate) onUpdate(data);
                    }
                };

                self.handle.subscribe(config.service, config.topic, config.priority, config.throttling, callback, onUnavailable);
                if (self.isDebug()) {
                    self.logDebug("Opened subscription for " + _subId2String(config));  // TODO should say opened interest or something
                }

            },

            _closeAllSubscriptions: function () {
                function _invalidateStreamDataInState(config) {
                    if (config.stateKey) {
                        var s = {};
                        s[config.stateKey] = false;
                        self.setState(s);
                    }
                }

                var self = this;
                if (self.subscribed) {
                    var configs = self._getSubscriptionConfigsFor(self.props);
                    if (configs && _.isArray(configs) && configs.length > 0) {
                        self.logDebug("Closing subscriptions on request...");
                        configs.forEach(self._unsubscribeFromStream);

                        if (self.componentMounted) {
                            configs.forEach(_invalidateStreamDataInState);
                        }
                    }
                    if (self.componentMounted) self.subscribed = false;
                }
            },


            _reopenAllSubscriptions: function () {
                var self = this;
                if (!self.state || !self.subscribed) {
                    var configs = self._getSubscriptionConfigsFor(self.props);
                    if (configs && _.isArray(configs) && configs.length > 0) {
                        self.logDebug("Reopening subscriptions on request...");
                        configs.forEach(self._subscribeToStream);
                    }
                    self.subscribed = true;
                }
            },


            _visibilityMonitorEnabled: function () {
                return this.refs.monitorVisibility;
            },

            _checkVisibility: function () {
                var self = this;
                var element = self.refs.monitorVisibility;
                if (this._visibilityMonitorEnabled()) {
                    var dom = element.getDOMNode();
                    var lastState = self.state.visibility;
                    if (lastState === undefined) lastState = false;
                    var currentlyVisible = $(dom).visible(true);
                    if (lastState != currentlyVisible) {
                        this.setState({visibility: currentlyVisible});
                        if (currentlyVisible) {
                            self._reopenAllSubscriptions();
                        } else {
                            self._closeAllSubscriptions();
                        }
                    }
                }
            },


            _refresh: function () {
                this.forceUpdate();
            }


        };

    });