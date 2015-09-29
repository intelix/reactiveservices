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
                return {data: false};
            },


            subscriptionConfig: function (props) {
                return [
                    {
                        service: "trading-engine",
                        topic: "trade:" + props.tradeId,
                        stateKey: "data"
                    }
                ];
            },

            render: function () {

                var data = this.state.data;

                if (!data) return <div ref="monitorVisibility">Loading ...</div>;

                return <div ref="monitorVisibility">Trade #{data.id}, Symbol {data.symbol} @ {data.price} by {data.trader} at {data.date}</div>;
            }
        });

    });