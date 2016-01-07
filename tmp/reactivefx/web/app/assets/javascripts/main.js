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

(function (requirejs) {
    'use strict';

    requirejs.config({
        packages: ['app', {name: 'core', location: '../lib/rs-core-js/js'}],
        paths: {
            'jquery': '../lib/jquery/jquery',
            'react': "../lib/react/react-with-addons",
            'lodash': "../lib/lodash/lodash.min",
            'signals': "../lib/js-signals/signals.min",
            'classnames': "../lib/classnames/index"
        },
        shim: {
            'jquery': {exports: "$"},
            'lodash': {exports: "_"}
        },
        config: {
            'core/socket': {
                endpoints: ["ws://localhost:8080"]
            },
            'core/logging': {
                logLevel: 'warn'
            }
        }
    });

    requirejs(["app/main", "core"], function (app) {
        app();
    });


})(requirejs);