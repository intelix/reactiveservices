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

define(['react', 'auth', 'appevents'], function (React, Auth, Appevents) {

    Auth.signals.accessChanged.add(function() {
        Appevents.signals.ApplicationRefreshRequest.dispatch();
    });

    return {
        authSubscriptions:  function (props) {
            return [
                {
                    service: "auth",
                    topic: 'token',
                    onUpdate: this.onAuthKey,
                    onUnavailable: this.onAuthServiceUnavailable,
                    priority: '1'
                },
                {
                    service: "auth",
                    topic: 'domains',
                    onUpdate: this.onDomainPermissions,
                    priority: '1'
                },
                {
                    service: "auth",
                    topic: 'subjects',
                    onUpdate: this.onSubjectPermissions,
                    priority: '1'
                }
            ];
        },

        componentWillMount: function () {
            this._addSubscriptionConfigBuilder(this.authSubscriptions);
        },

        onAuthServiceUnavailable: function() {
            Auth.resetToken();
        },

        onAuthKey: function (data) {
            if (data) {
                Auth.setToken(data);
                this.logDebug("Auth token received: " + data);
            } else {
                this.logWarn("Blank auth token received: " + data);

                Auth.resetToken();
            }
        },
        onDomainPermissions: function (data) {
            Auth.setDomainPermissions(data);
            this.logInfo("Domain permissions received: " + data);
        },
        onSubjectPermissions: function (data) {
            Auth.setSubjectPermissions(data);
            this.logInfo("Subject permissions received: " + data);
        },

        onAuthSuccess: function (data) {
            this.logInfo("Authentication successful");
        },
        onAuthFailure: function (data) {
            this.logInfo("Authentication unsuccessful");
            Auth.resetToken();
        },
        initiateLogin: function() {
            if (Auth.hasToken()) {
                this.logInfo("Authenticating with token: " + Auth.getToken());
                this.sendSignal("auth", "tauth", Auth.getToken(), false, 5, this.onAuthSuccess, this.onAuthFailure);
                Auth.setAuthenticationPending();
            } else {
                Auth.resetSession();
            }
        },

        performCredentialsAuthentication: function (user, passw) {
            Auth.resetToken();
            Auth.setAuthenticationPending();
            this.logInfo("Authenticating with credentials, as " + user);
            this.sendSignal("auth", "cauth", {u: user, p: passw}, false, 5, this.onAuthSuccess, this.onAuthFailure);
        },

        onConnected: function () {
            this.initiateLogin();
        },

        onDisconnected: function() {
            Auth.resetSession();
        },

        isAuthenticationPending: Auth.isAuthenticationPending,

        render: function () {
            var self = this;

            if (Auth.isSessionValid()) {
                return self.renderSecured();
            } else {
                return self.renderUnsecured();
            }

        }

    };


});