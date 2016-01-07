define(['react', 'core/mixin', './RequestQuoteForm', './Tiles', './KillButton'], function (React, RSMixin, RequestQuoteForm, Tiles, KillButton) {

    return React.createClass({

        mixins: [RSMixin],

        subscriptionConfig: function (props) {
            return [
                {
                    service: "price-service",
                    topic: "pings",
                    onUpdate: this.onUpdate,
                    priority: '1'
                }
            ];
        },

        onUpdate: function (u) {
            this.sendSignal("price-service", "pong", u);
        },

        render: function () {
            return (
                <div className="container">
                    <div className="row">
                        <div className="col-md-12">
                            <h1><strong>Reactive FX</strong></h1>
                        </div>
                    </div>
                    <div className="row">
                        <div className="col-sm-4"><RequestQuoteForm /></div>
                        <div className="col-sm-3"></div>
                        <div className="col-sm-2"><KillButton /></div>
                    </div>
                    <div className="row"><Tiles /></div>
                </div>
            );
        }

    });

});