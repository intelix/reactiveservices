package rs.core.services.endpoint.akkastreams

import akka.actor.{ActorRef, PoisonPill, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.Messages.ServiceOutboundMessage
import rs.core.services.SequentialMessageIdGenerator
import rs.core.services.internal.InternalMessages.DownstreamDemandRequest
import rs.core.sysevents.ref.ComponentWithBaseSysevents


trait ServicePortStreamSourceSysevents extends ComponentWithBaseSysevents {

  val Cancelled = "Cancelled".info
  val TerminatingOnRequest = "TerminatingOnRequest".info
  val DemandProduced = "DemandProduced".trace
  val OnNext = "OnNext".trace

  override def componentId: String = "ServicePort.StreamSource"
}

object ServicePortStreamSource {
  def props(streamAggregator: ActorRef, token: String) = Props(classOf[ServicePortStreamSource], streamAggregator, token)
}

class ServicePortStreamSource(streamAggregator: ActorRef, token: String)
  extends ActorWithComposableBehavior
  with ActorPublisher[Any]
  with ServicePortStreamSourceSysevents {

  private val messageIdGenerator = new SequentialMessageIdGenerator()

  onMessage {
    case Request(n) =>
      DemandProduced('new -> n, 'total -> totalDemand)
      streamAggregator ! DownstreamDemandRequest(messageIdGenerator.next(), n)
    case Cancel =>
      Cancelled()
      streamAggregator ! PoisonPill
      context.stop(self)
    case m: ServiceOutboundMessage =>
      OnNext('demand -> totalDemand)
      onNext(m)
  }


  onActorTerminated { ref =>
    if (ref == streamAggregator) {
      TerminatingOnRequest()
      onCompleteThenStop()
    }
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(streamAggregator)
  }

  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('token -> token)


}
