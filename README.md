# What is it?

Framework for building resilient streaming systems with single-page web fronts.

# Why?

* Low latency (sub-ms) and high throughput (100,000s/sec) 
* Out-of-the-box Fault tolerance and High Availability with automatic service discovery, split brain recovery and unlimited possibilities of scaling
* Micro-services oriented
* Powerful testing framework and DSL
* Powered by Reactive Streams and Akka
* Scala and Java (coming soon) API

Sample application built with the framework:
!["screen1"](https://raw.githubusercontent.com/intelix/reactfx/master/screen1.png "screen1")
See [https://github.com/intelix/reactfx](https://github.com/intelix/reactfx)

# 1 minute intro

Create a service:

```scala
class PriceSourceService(id: String) extends StatelessServiceActor(id) {
}
```

Map subject subscription to a topic:

```scala
onSubjectMapping {
    case Subject(_, TopicKey(instrument), _) => instrument
}
```

Set topic lifecycle hooks:

```scala
onStreamActive {
  case SimpleStreamId(instrument) => instruments += instrument
}
```

```scala
onStreamPassive {
  case SimpleStreamId(instrument) => instruments -= instrument
}
```

Publish data:

```scala
instrument !# ("price" -> getPrice)
```

Define and configure the service:

```hocon
node.services.price-source = "rs.examples.stocks.PriceSourceService"
price-source.cfg-value = 12345
```

On the client side, subscribe to the stream:

```javascript
return React.createClass({

    mixins: [RSMixin],

    getInitialState: function () {
        return {data: false};
    },

    subscriptionConfig: function (props) {
        return [
            {
                service: "price-source",
                topic: "AUDUSD",
                ostateKey: "data"
            }
        ];
    },
    
    onTrade: function () {
        var data = this.state.data;
        this.sendSignal("price-source", "trade", {instrument: "AUDUSD", price: data.price});
    },
    
    render: function () {

        var data = this.state.data;

        if (!data) return <div>Loading ...</div>;

        var price = <span>{data.price} <a href="#" onClick={this.onTrade}>Buy</a></span>;

        return <span>{data.price} <a href="#" onClick={this.onTrade}>Buy</a></span>;
    }

});
```

Receive the trade signal on the service side:

```scala
onSignal {
    case (Subject(_, TopicKey("trade"), UserId(uid)), data: String) =>
      // pocess data
      SignalOk()
}
  
```

Refer to [reactiveservices-examples/stocks](https://github.com/intelix/reactiveservices-examples/tree/master/stocks) for a complete runnable sample app.


