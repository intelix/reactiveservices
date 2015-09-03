package rs.core.services.internal

import akka.actor.ActorRef
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.internal.acks.{Acknowledgeable, Acknowledgement}

trait MessageAcknowledging extends ActorWithComposableBehavior {

  private def acknowledge(m: Acknowledgeable) = {
    val ackTo = m.acknowledgeTo getOrElse sender()
    ackTo ! Acknowledgement(m.messageId)
    logger.info(s"!>>>> Acknowledged ${m.messageId}")
  }

  def shouldProcessAcknowledgeable(sender: ActorRef, m: Acknowledgeable) = true
  
  onMessage {
    case m: Acknowledgeable if shouldProcessAcknowledgeable(sender(), m) =>
      processMessage(m.payload)
      acknowledge(m)
  }

}
