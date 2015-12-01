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

        var ServiceNotAvailable = "Service not available";

        return React.createClass({

            mixins: [RSMixin],

            getInitialState: function () {
                return {
                    connected: false, // automatically managed
                    counterValue: ServiceNotAvailable,
                    jcounterValue: ServiceNotAvailable
                };
            },


            subscriptionConfig: function (props) {
                return [
                    {
                        service: "counter-service",
                        topic: "counter",
                        stateKey: "counterValue",
                        onUnavailable: this.onCounterUnavailable
                    },
                    {
                        service: "j-counter-service",
                        topic: "counter",
                        stateKey: "jcounterValue",
                        onUnavailable: this.onJCounterUnavailable
                    }
                ];
            },

            onCounterUnavailable: function () {
                this.setState({counterValue: ServiceNotAvailable});
            },
            onJCounterAvailable: function () {
                this.setState({counterValue: ServiceNotAvailable});
            },

            onJCounterUnavailable: function () {
                this.setState({jcounterValue: ServiceNotAvailable});
            },

            onStopCounter: function () {
                this.sendSignal("counter-service", "stop");
            },
            onResetCounter: function () {
                this.sendSignal("counter-service", "reset");
            },
            onResetJCounter: function () {
                this.sendSignal("j-counter-service", "reset");
            },

            render: function () {

                var state = this.state;

                if (state.connected) {
                    var counterReset = (state.counterValue == ServiceNotAvailable)
                        ? ""
                        : <a href="#" onClick={this.onResetCounter}>Reset</a>;

                    var jcounterReset = (state.jcounterValue == ServiceNotAvailable)
                        ? ""
                        : <a href="#" onClick={this.onResetJCounter}>Reset</a>;

                    var counterStop = (state.counterValue == ServiceNotAvailable)
                        ? ""
                        : <a href="#" onClick={this.onStopCounter}>Stop</a>;


                    return (
                        <span>
                        <div>Counter (Scala based): {state.counterValue} {counterReset} {counterStop}</div>
                        <div>Counter (Java based) : {state.jcounterValue} {jcounterReset}</div>
                    </span>
                    );
                } else
                    return <div>Connecting... </div>;
            }
        });

    });