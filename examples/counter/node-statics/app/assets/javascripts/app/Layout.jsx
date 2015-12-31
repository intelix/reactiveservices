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

define(['react', 'core/mixin'],
    function (React, RSMixin) {

        var ServiceNotAvailable = "Service not available";

        return React.createClass({

            mixins: [RSMixin],

            getInitialState: function () {
                return {
                    connected: false, // automatically managed
                    value: ServiceNotAvailable
                };
            },


            subscriptionConfig: function (props) {
                return [
                    {
                        service: "counter-service",
                        topic: "counter",
                        stateKey: "value",
                        onUnavailable: this.onCounterUnavailable
                    }
                ];
            },

            onCounterUnavailable: function () {
                this.setState({value: ServiceNotAvailable});
            },
            onStopCounter: function () {
                this.sendSignal("counter-service", "stop");
            },
            onResetCounter: function () {
                this.sendSignal("counter-service", "reset");
            },

            render: function () {

                var state = this.state;

                if (state.connected) {
                    var counterReset = (state.value == ServiceNotAvailable)
                        ? ""
                        : <a href="#" onClick={this.onResetCounter}>Reset</a>;

                    var counterStop = (state.value == ServiceNotAvailable)
                        ? ""
                        : <a href="#" onClick={this.onStopCounter}>Stop</a>;


                    return (
                        <span>
                            <div>Value: {state.value} {counterReset} {counterStop}</div>
                        </span>
                    );
                } else
                    return <div>Connecting... </div>;
            }
        });

    });