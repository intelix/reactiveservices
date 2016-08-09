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
import React from 'react';
import AppEvents from './appevents';
import Socket from './socket';
import Subscriptions from './subscriptions';
import Auth from './auth';


function _subId2String(config) {
  return config ? config.service + "#" + config.topic : "Nil";
}



export var Subscriber = (ComposedComponent, ...subBuilders) => class extends React.Component {
  constructor(props) {
    super(props);

  }


  render() {
    return <ComposedComponent {...this.props} />
  }
};

class SomeComponent extends React.Component {

}


export default {

  socket: Socket,

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

  /* permissions */

  hasSubjectPermission: Auth.hasSubjectPermission,
  hasDomainPermission: Auth.hasDomainPermission,

  /* misc */

  sendSignal: function (service, topic, data, orderingGroup, expireInSeconds, onSuccess, onFailure) {
    if (_.isObject(this.handle) && this.handle.signal) this.handle.signal(service, topic, data, orderingGroup, expireInSeconds, onSuccess, onFailure);
  },

  /* ReactJS hooks */

  componentWillMount: function () {
    var self = this;
    self.componentMounting = true;

    this.setState({connected: false});
    if (self.subscribeToEvents) {
      var eventslist = self.subscribeToEvents();
      this.subscribedToEvents = eventslist;
      eventslist.forEach(function (el) {
        if (el[0] && el[1]) el[0].add(el[1]);
      });
    }
  },

  componentDidMount: function () {
    var self = this;
    self.componentMounted = true;
    self.componentMounting = false;
    self._addSubscriptionConfigBuilder(self.subscriptionConfig);

    self.handle = Subscriptions.createHandle();

    Socket.signals.connected.add(self._onConnected);
    Socket.signals.disconnected.add(self._onDisconnected);

    if (Socket.isConnected()) {
      self._onConnected();
    } else {
      self._onDisconnected();
    }

    if (self._visibilityMonitorEnabled()) {
      AppEvents.signals.ApplicationVisibilityChanged.add(self._checkVisibility);
      self._checkVisibility();
    }

    AppEvents.signals.ApplicationRefreshRequest.add(self._refresh);

    function _scheduleVisibilityCheck() {
      self.visibilityCheckTimeout = setTimeout(_performVisibilityCheck, 1000);
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
    self.componentMounting = false;
    self.configBuilders = [];

    Socket.signals.connected.remove(self._onConnected);
    Socket.signals.disconnected.remove(self._onDisconnected);

    if (self.visibilityCheckTimeout) {
      clearTimeout(self.visibilityCheckTimeout);
      self.visibilityCheckTimeout = false;
    }

    if (self.handle) {
      self._closeAllSubscriptions();

      self.handle.terminate();
      self.handle = null;
    }
    if (self.subscribedToEvents) {
      var eventslist = self.subscribedToEvents;
      eventslist.forEach(function (el) {
        // console.info("!>>>> Unsubscribing from " + el[0] + " : " + el[1]);
        if (el[0] && el[1]) el[0].remove(el[1]);
      });
      self.subscribedToEvents = [];
    }

    if (self._visibilityMonitorEnabled()) {
      AppEvents.signals.ApplicationVisibilityChanged.remove(self._checkVisibility);
    }

    AppEvents.signals.ApplicationRefreshRequest.remove(self._refresh);

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


  _onConnected: function () {
    var self = this;
    if (!self._visibilityMonitorEnabled() || (self.state && (self.state.visibility === true))) {
      if (self.isDebug()) {
        self.logDebug("Server connection established, re-subscribing");
      }
      self._reopenAllSubscriptions();
    }
    if (!self.state || !self.state.connected) {
      if (self.componentMounted || self.componentMounting) self.setState({connected: true});
      if (self.onConnected) self.onConnected();
    }
  },

  _onDisconnected: function () {
    var self = this;
    if (self.isDebug()) {
      self.logDebug("Server connection lost");
    }
    self._closeAllSubscriptions();
    if (self.state && self.state.connected) {
      if (self.componentMounted || self.componentMounting) self.setState({connected: false});
      if (self.onDisconnected) self.onDisconnected();
    }
  },


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
      self.logDebug("Dropped interest for " + _subId2String(config));
    }

  },

  _subscribeToStream: function (config) {
    var self = this;

    var onUpdate = (!_.isUndefined(config.onUpdate) && _.isFunction(config.onUpdate)) ? config.onUpdate : false;
    var onError = (!_.isUndefined(config.onError) && _.isFunction(config.onError)) ? config.onError : false;
    var onUnavailable = config.onUnavailable;

    var stateKey = config.stateKey;
    if (stateKey && (self.componentMounted || self.componentMounting)) {
      var partialStateUpdate = {};
      partialStateUpdate[stateKey] = false;
      self.setState(partialStateUpdate);
    }

    var callbacks = {
      onUpdate: function (data) {
        if (self.componentMounted || self.componentMounting) {
          if (stateKey) {
            var partialStateUpdate = {};
            var existing = self.state[stateKey];
            // if (!_.isEqual(existing, data)) { // TODO Test this thoroughly
              partialStateUpdate[stateKey] = data;
              self.setState(partialStateUpdate);
              // console.info(`!>>> Updated ${stateKey} with ${JSON.stringify(partialStateUpdate)}`)
            // }
          }
          if (onUpdate) onUpdate(data);
        }
      },
      onError: function () {
        if (onError) onError();
      }
    };

    self.handle.subscribe(config.service, config.topic, config.priority, config.throttling, callbacks, onUnavailable);
    if (self.isDebug()) {
      self.logDebug("Added interest for " + _subId2String(config));
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
        self.logDebug("Closing subscriptions: " + configs.length);
        configs.forEach(self._unsubscribeFromStream);

        if (self.componentMounted || self.componentMounting) {
          configs.forEach(_invalidateStreamDataInState);
        }
      }
      if (self.componentMounted || self.componentMounting) self.subscribed = false;
    }
  },


  _reopenAllSubscriptions: function () {
    var self = this;
    if (!self.state || !self.subscribed) {
      var configs = self._getSubscriptionConfigsFor(self.props);
      if (configs && _.isArray(configs) && configs.length > 0) {
        self.logDebug("Re-opening subscriptions: " + configs.length);
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
      var dom = element;
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


}
