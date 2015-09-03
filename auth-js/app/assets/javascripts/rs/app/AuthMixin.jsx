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

define(['react', 'auth'], function (React, Auth) {

    return {
        authSubscriptions:  function (props) {
            return [
                {
                    service: "access_control",
                    topic: 'token',
                    onData: this.onAuthKey
                },
                {
                    service: "access_control",
                    topic: 'permissions',
                    onData: this.onPermissions
                },
                {
                    service: "user_auth",
                    topic: 'authenticate',
                    onData: this.onLoginResponse
                }
            ];
        },

        componentWillMount: function () {
            this._addSubscriptionConfigBuilder(this.authSubscriptions);
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
        onPermissions: function (data) {
            Auth.setPermissions(data);
            this.logDebug("Permissions received: " + data);
        },
        onLoginResponse: function (data) {
            if (data && data.success) {
                this.logInfo("Successfully authenticated, server response: " + data.msg);
            } else {
                this.logInfo("Authentication failed, server response: " + (data && data.msg ? data.msg : "n/a"));
                Auth.resetToken();
            }
        },
        initiateLogin: function() {
            if (Auth.hasToken()) {
                this.logInfo("Authenticating with token: " + Auth.getToken());
                this.sendSignal("auth", "authenticate", {t: Auth.getToken()});
                Auth.setAuthenticationPending();
            } else {
                Auth.resetSession();
            }
        },

        performCredentialsAuthentication: function (user, passw) {
            Auth.resetToken();
            Auth.setAuthenticationPending();
            this.logInfo("Authenticating with credentials, as " + user);
            this.sendSignal("auth", "authenticate", {l: user, p: passw});
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