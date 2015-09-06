package rs.core.services.endpoint.akkastreams

import akka.actor.{ActorRef, PoisonPill, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.Messages.{ServiceOutboundMessage, ServiceNotAvailable, StreamStateUpdate}
import rs.core.services.SequentialMessageIdGenerator
import rs.core.services.internal.InternalMessages.DownstreamDemandRequest

object ServiceSubscriptionStreamSource {
  def props(streamAggregator: ActorRef) = Props(classOf[ServiceSubscriptionStreamSource], streamAggregator)
}

class ServiceSubscriptionStreamSource(streamAggregator: ActorRef)
  extends ActorWithComposableBehavior
  with ActorPublisher[Any] {

  private val messageIdGenerator = new SequentialMessageIdGenerator()

  onMessage {
    case Request(n) =>
      logger.info("!>>>>> REQUESTED: " + n)
      streamAggregator ! DownstreamDemandRequest(messageIdGenerator.next(), n)
    case Cancel =>
      streamAggregator ! PoisonPill
      context.stop(self)
    case m: ServiceOutboundMessage =>
      logger.info("!>>>>> ON NEXT, current demand: " + totalDemand)
      onNext(m)
  }


  onActorTerminated { ref =>
    if (ref == streamAggregator) onCompleteThenStop()
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(streamAggregator)
  }

  override def componentId: String = "ServiceSubscriptionStreamSource"
}
