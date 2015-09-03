package rs.core.services.internal

import akka.actor.ActorRef
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.internal.InternalMessages.DownstreamDemandRequest
import rs.core.services.internal.acks.Acknowledgeable

trait StreamDemandBinding extends ActorWithComposableBehavior with DuplicateMessageTracker with MessageAcknowledging {

  def onConsumerDemand(sender: ActorRef, demand: Long)

  private def processDemand(m: DownstreamDemandRequest) = if (isNotDuplicate(sender(), "DownstreamDemandRequest", m.messageId)) onConsumerDemand(sender(), m.count)


  onMessage {
    case m: DownstreamDemandRequest => processDemand(m)
  }

  override def shouldProcessAcknowledgeable(sender: ActorRef, m: Acknowledgeable): Boolean = {
    logger.info("!>>>>>>>>  CHECKING " + m)
    m.payload match {
      case m: DownstreamDemandRequest =>
        logger.info("!>>>>   !!! TRUE")
        true
      case _ => super.shouldProcessAcknowledgeable(sender, m)
    }
  }
}
