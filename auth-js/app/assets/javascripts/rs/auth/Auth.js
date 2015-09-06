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

define(['logging', 'signals', 'cookies'], function (Log, Signal, Cookies) {

    
    var securityCookie = "_session_token";
    var defaultExpiryDays = 30;
    var token = Cookies.readCookie(securityCookie);


    var validToken = false;
    var authPending = false;
    var permissions = false;

    var signals = {
        accessChanged: new Signal(),
        permissionsChanged: new Signal()
    };

    function updateToken(newToken) {
        token = newToken;
        Cookies.createCookie(securityCookie, newToken, defaultExpiryDays);
        validToken = newToken;
        authPending = false;
        raiseAuthAccessChanged();
    }

    function getCachedToken() {
        return token;
    }

    function resetSession() {
        validToken = false;
        authPending = false;
        permissions = false;
        raiseAuthAccessChanged();
    }
    function resetToken() {
        updateToken(false);
        raiseAuthAccessChanged();
    }

    function setAuthenticationPending() {
        authPending = true;
        validToken = false;
        permissions = false;
        raiseAuthAccessChanged();
    }

    function isAuthenticationPending() {
        return false;
    }

    function setPermissions(permSet) {
        signals.permissionsChanged.dispatch(permSet);
        permissions = permSet;
        authPending = false;
        raiseAuthAccessChanged();
    }

    function isSessionValid() {
        return !authPending && validToken && permissions;
    }

    function raiseAuthAccessChanged() {
        signals.accessChanged.dispatch(isSessionValid());
    }

    return {
        isSessionValid: isSessionValid,
        signals: signals,

        setToken: updateToken,
        hasToken: getCachedToken,
        getToken: getCachedToken,
        setPermissions: setPermissions,
        resetSession: resetSession,
        resetToken: resetToken,
        setAuthenticationPending: setAuthenticationPending,
        isAuthenticationPending: isAuthenticationPending

    };

});