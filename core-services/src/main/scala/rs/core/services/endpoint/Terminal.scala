package rs.core.services.endpoint

import akka.actor.ActorRef
import rs.core.actors.ActorWithComposableBehavior
import rs.core.stream._
import rs.core.services.internal.{StreamAggregatorActor, DemandProducerContract}

trait Terminal
  extends ActorWithComposableBehavior
  with StreamConsumer
  with StringStreamConsumer
  with CustomStreamConsumer
  with DictionaryMapStreamConsumer
  with SetStreamConsumer
  with ListStreamConsumer
  with DemandProducerContract {


  override val streamAggregator: ActorRef = context.actorOf(StreamAggregatorActor.props(), "stream-aggregator")

  startDemandProducerFor(streamAggregator, withAcknowledgedDelivery = false)


  override def preStart(): Unit = {
    super.preStart()
    startDemandProducerFor(streamAggregator, withAcknowledgedDelivery = false)
  }

  final override def onDemandFulfilled(): Unit = upstreamDemandFulfilled(streamAggregator, 1)
}
