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

(function() {


    require.config({

        /*noinspection */
        paths: {
            jquery: "/assets/javascripts/jquery.min",
            react: "/assets/javascripts/react-with-addons.min",
            jvisible: "/assets/javascripts/jquery.visible.min",
            jdataview: "/assets/javascripts/jdataview",
            lodash: "/assets/javascripts/lodash.min",
            uuid: "/assets/javascripts/uuid",
            signals: "/assets/javascripts/signals.min"
        },
        packages: [
        ],
        shim: {
            jvisible: {
                deps: ["jquery"]
            },
            jquery: {
                exports: "$"
            },
            lodash: {
                exports: "_"
            }
        }
    });

    require(['jvisible']);
    require(['uuid']);
    require(['lodash']);

    require([
        'jvisible',
        'uuid',
        'lodash',
        'jdataview',
        'signals'
    ]);

})();