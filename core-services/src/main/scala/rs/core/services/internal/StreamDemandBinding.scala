package rs.core.services.internal

import akka.actor.ActorRef
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.internal.InternalMessages.DownstreamDemandRequest
import rs.core.services.internal.acks.Acknowledgeable
import rs.core.sysevents.ref.ComponentWithBaseSysevents

trait StreamDemandBindingSysevents extends ComponentWithBaseSysevents {
  val DuplicateDemandRequest = "DuplicateDemandRequest".trace
}

trait StreamDemandBinding extends ActorWithComposableBehavior with DuplicateMessageTracker with MessageAcknowledging with StreamDemandBindingSysevents {

  def onConsumerDemand(sender: ActorRef, demand: Long)

  private def processDemand(m: DownstreamDemandRequest) = if (isNotDuplicate(sender(), "DownstreamDemandRequest", m.messageId)) {
    onConsumerDemand(sender(), m.count)
  } else {
    DuplicateDemandRequest('sender -> sender(), 'payload -> m)
  }


  onMessage {
    case m: DownstreamDemandRequest => processDemand(m)
  }

  override def shouldProcessAcknowledgeable(sender: ActorRef, m: Acknowledgeable): Boolean = {
    m.payload match {
      case m: DownstreamDemandRequest => true
      case _ => super.shouldProcessAcknowledgeable(sender, m)
    }
  }
}
