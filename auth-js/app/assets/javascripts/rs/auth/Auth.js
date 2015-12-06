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
    var domainPermissions = false;
    var subjectPermissions = false;

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
        domainPermissions = false;
        subjectPermissions = false;
        raiseAuthAccessChanged();
    }
    function resetToken() {
        updateToken(false);
        raiseAuthAccessChanged();
    }

    function setAuthenticationPending() {
        authPending = true;
        validToken = false;
        domainPermissions = false;
        subjectPermissions = false;
        raiseAuthAccessChanged();
    }

    function isAuthenticationPending() {
        return false;
    }

    function setDomainPermissions(permSet) {
        domainPermissions = permSet;
        authPending = !domainPermissions || !subjectPermissions;
        raisePermissionsChanged();
        raiseAuthAccessChanged();
    }

    function setSubjectPermissions(permSet) {
        var sorted = _.sortBy(permSet, function(next) {
            return -next.length * (_.startsWith(next, "-") ? 100 : 1);
        });
        subjectPermissions = _.map(sorted, function(next) {
            return {
                allow: !_.startsWith(next, "-"),
                pattern: _.startsWith(next, "-") || _.startsWith(next, "+") ? next.substring(1) : next
            };
        });
        authPending = !domainPermissions || !subjectPermissions;
        raisePermissionsChanged();
        raiseAuthAccessChanged();
    }

    function raisePermissionsChanged() {
        signals.permissionsChanged.dispatch({domain: domainPermissions, subjects: subjectPermissions});
    }

    function isSessionValid() {
        return !authPending && validToken && domainPermissions && subjectPermissions;
    }

    function raiseAuthAccessChanged() {
        signals.accessChanged.dispatch(isSessionValid());
    }

    function hasDomainPermission(domain) {
        return $.inArray(domain, domainPermissions) > -1;
    }
    function hasSubjectPermission(service, topic) {
        var key = service + "/" + topic;
        var found = _.find(subjectPermissions, function(next) {
           return !next.pattern || 0 === next.pattern.length || key.indexOf(next.pattern) > -1;
        });
        return found && found.allow;
    }

    return {
        isSessionValid: isSessionValid,
        signals: signals,

        setToken: updateToken,
        hasToken: getCachedToken,
        getToken: getCachedToken,
        setDomainPermissions: setDomainPermissions,
        setSubjectPermissions: setSubjectPermissions,
        hasDomainPermission: hasDomainPermission,
        hasSubjectPermission: hasSubjectPermission,
        resetSession: resetSession,
        resetToken: resetToken,
        setAuthenticationPending: setAuthenticationPending,
        isAuthenticationPending: isAuthenticationPending

    };

});