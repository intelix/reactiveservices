define(['react', 'core/mixin'], function (React, RSMixin) {

    return React.createClass({

        mixins: [RSMixin],

        getInitialState: function () {
            return {price: "N/A", source: ""}
        },

        subscriptionConfig: function (props) {
            return [
                {
                    service: "price-service",
                    topic: "ccy:" + props.pairId,
                    onUpdate: this.onUpdate,
                    onUnavailable: this.onUnavailable
                    //throttling: 1000
                }
            ];
        },

        onUpdate: function (u) {
            var price = _.padRight(u.price / 100000, 7, '0');
            var source = "Server " + u.source;
            this.setState({price: price, source: source});
        },

        onUnavailable: function (u) {
            this.setState({price: "N/A", source: ""});
        },

        render: function () {
            var state = this.state;
            return (
                <div className="tile blue">
                    <div className="price">{state.price}</div>
                    <div className="ccyPair">{this.props.pair}</div>
                    <div className="source">{state.source}</div>
                </div>
            );
        }

    });

});