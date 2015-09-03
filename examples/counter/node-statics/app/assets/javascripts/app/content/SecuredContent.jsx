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

define(['react', 'rs_mixin'],
    function (React, RSMixin) {

        return React.createClass({

            mixins: [RSMixin],

            getInitialState: function () {
                return {map: {}, list: [], set: [], string: "", available: "no", signalCounter: 0};
            },


            subscriptionConfig: function (props) {
                return [
                    {
                        service: "pub",
                        topic: "set",
                        stateKey: "set",
                        throttling: 10000,
                        onUnavailable: this.onUnavailable,
                        onUpdate: this.onAvailable
                    },
                    {service: "pub", topic: "string", stateKey: "string"},
                    {service: "pub", topic: "map", stateKey: "map"},
                    {service: "pub", topic: "list", stateKey: "list"}
                ];
            },

            onUnavailable: function () {
                this.setState({available: "no"});
            },
            onAvailable: function () {
                this.setState({available: "yes"});
            },

            onSendSignalRequest: function () {

                var counter = this.state.signalCounter;
                this.setState({signalCounter: counter + 1});

                function onOk(pay) {
                    console.info("!>>>> Signal " + counter + ": OK, payload: " + JSON.stringify(pay));
                }

                function onFailed(pay) {
                    console.info("!>>>> Signal " + counter + ": FAILED, payload: " + JSON.stringify(pay));
                }

                this.sendSignal("pub", "signal", "hello-" + counter, false, 5, onOk, onFailed);
                console.info("!>>>> Signal " + counter + ": SENT");

            },

            render: function () {

                var state = this.state;

                return (
                    <span>
                        <div>Map: {JSON.stringify(state.map)}</div>
                        <div>List: {JSON.stringify(state.list)}</div>
                        <div>Set: {JSON.stringify(state.set)}</div>
                        <div>String: {state.string}</div>
                        <div>Service available: {state.available}</div>
                        <div><a href="#" onClick={this.onSendSignalRequest}>Send signal</a></div>
                    </span>
                );
            }
        });

    });