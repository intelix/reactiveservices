define(['react', 'core/mixin'], function (React, RSMixin) {

    return React.createClass({

        mixins: [RSMixin],

        killPriceServer: function () {
            this.sendSignal("price-service", "kill");
        },

        render: function () {
            return (
                <div className="tile white">
                    <div className="center-block">
                        <form className="form-inline">
                            <button type="button" className="btn btn-danger" onClick={this.killPriceServer}>Kill Price Datasource</button>
                        </form>
                    </div>
                </div>
            );
        }

    });

});