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

define(['react', 'rs_mixin', './Trade'],
    function (React, RSMixin, Trade) {

        return React.createClass({

            mixins: [RSMixin],

            getInitialState: function () {
                return {entries: [], available: false};
            },


            subscriptionConfig: function (props) {
                return [
                    {
                        service: "trading-engine",
                        topic: "trades",
                        stateKey: "entries",
                        onUnavailable: this.onUnavailable,
                        onUpdate: this.onAvailable
                    }
                ];
            },

            onUnavailable: function () {
                this.setState({available: false});
            },
            onAvailable: function () {
                this.setState({available: true});
            },

            render: function () {

                if (!this.state.available) return <div>Service not available</div>;

                var entries = this.state.entries && _.isArray(this.state.entries) ? this.state.entries : [];

                return (
                    <div>
                        {entries.map(function(i) {
                            return <Trade key={i} tradeId={i}/>
                            })}
                    </div>
                );
            }
        });

    });