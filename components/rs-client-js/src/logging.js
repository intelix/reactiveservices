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
import Config from './config';


var DEBUG = 1;
var INFO = 2;
var WARN = 3;
var ERROR = 4;

function nameToID(name) {
  if (name && "debug" == name.toLowerCase()) return DEBUG;
  if (name && "info" == name.toLowerCase()) return INFO;
  if (name && "warn" == name.toLowerCase()) return WARN;
  return ERROR;
}

var logLevel = _.isUndefined(Config.logLevel) ? ERROR : nameToID(Config.logLevel);

function format(prefix, msg) {
  return new Date().toISOString() + ": " + (prefix ? prefix + ": " + msg : msg);
}

export  default {

  isDebug: function () {
    return (this.logLevel && this.logLevel() <= DEBUG) || (!this.logLevel && logLevel <= DEBUG);
  },

  isInfo: function () {
    return (this.logLevel && this.logLevel() <= INFO) || (!this.logLevel && logLevel <= INFO);
  },

  isWarn: function () {
    return (this.logLevel && this.logLevel() <= WARN) || (!this.logLevel && logLevel <= WARN);
  },

  logDebug: function (prefix, msg) {
    if (this.isDebug()) {
      console.debug(format(prefix, msg));
    }
  },

  logInfo: function (prefix, msg) {
    if (this.isInfo()) {
      console.info(format(prefix, msg));
    }
  },

  logWarn: function (prefix, msg) {
    console.warn(format(prefix, msg));
  },

  logError: function (prefix, msg) {
    console.error(format(prefix, msg));
  }

}
