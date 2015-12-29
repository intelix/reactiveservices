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

(function() {

    require(["dependencies-3rdparty", "auth-config"], function() {

        require.config({

            /*noinspection */
            paths: {

                logging: "/assets/javascripts/rs/core/tools/Logging",
                cookies: "/assets/javascripts/rs/core/tools/Cookies",

                socket: "/assets/javascripts/rs/core/websocket/WebsocketConnectivity",
                codec: "/assets/javascripts/rs/core/codec/BaseCodec",
                streams: "/assets/javascripts/rs/core/handlers/StreamHandler",
                subscriptions: "/assets/javascripts/rs/core/subscriptions/Subscriptions",

                codec_map: "/assets/javascripts/rs/core/codec/DictionaryMapStreamCodec",
                handler_map: "/assets/javascripts/rs/core/handlers/DictionaryMapStreamHandler",

                codec_list: "/assets/javascripts/rs/core/codec/ListStreamCodec",
                handler_list: "/assets/javascripts/rs/core/handlers/ListStreamHandler",

                codec_set: "/assets/javascripts/rs/core/codec/SetStreamCodec",
                handler_set: "/assets/javascripts/rs/core/handlers/SetStreamHandler",

                codec_string: "/assets/javascripts/rs/core/codec/StringStreamCodec",
                handler_string: "/assets/javascripts/rs/core/handlers/StringStreamHandler",

                handler_ping: "/assets/javascripts/rs/core/handlers/PingHandler",

                appevents: "/assets/javascripts/rs/app/ApplicationEvents",
                rs_mixin: "/assets/javascripts/rs/app/ReactJSMixin"

            },
            packages: [
            ],
            shim: {
                codec: {
                    deps: ["socket", "jquery", "lodash", "logging"]
                },
                streams: {
                    deps: ["codec"]
                },
                subscriptions: {
                    deps: ["streams"]
                },
                rs_mixin: {
                    deps: ["subscriptions", "react"]
                },
                codec_map: {
                    deps: ["codec"]
                },
                handler_map: {
                    deps: ["codec_map", "streams"]
                },
                codec_list: {
                    deps: ["codec"]
                },
                handler_list: {
                    deps: ["codec_list", "streams"]
                },
                codec_set: {
                    deps: ["codec"]
                },
                handler_set: {
                    deps: ["codec_set", "streams"]
                },
                codec_string: {
                    deps: ["codec"]
                },
                handler_string: {
                    deps: ["codec_string", "streams"]
                }
            }
        });

        require([
            "logging",
            "codec",
            "streams",
            "subscriptions",
            "codec_map",
            "handler_map",
            "codec_list",
            "handler_list",
            "codec_set",
            "handler_set",
            "codec_string",
            "handler_string",
            "handler_ping"
        ]);

    });


})();