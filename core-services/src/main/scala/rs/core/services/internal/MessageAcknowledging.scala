package rs.core.services.internal

import akka.actor.ActorRef
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.internal.acks.{Acknowledgeable, Acknowledgement}
import rs.core.sysevents.ref.ComponentWithBaseSysevents

trait MessageAcknowledgingSysevents extends ComponentWithBaseSysevents {
  val AutoAcknowledged = "AutoAcknowledged".trace
}

trait MessageAcknowledging extends ActorWithComposableBehavior with MessageAcknowledgingSysevents {

  private def acknowledge(m: Acknowledgeable) = AutoAcknowledged { ctx =>
    val ackTo = m.acknowledgeTo getOrElse sender()
    ackTo ! Acknowledgement(m.messageId)
    ctx +('id -> m.messageId, 'with -> ackTo)
  }

  def shouldProcessAcknowledgeable(sender: ActorRef, m: Acknowledgeable) = true

  onMessage {
    case m: Acknowledgeable if shouldProcessAcknowledgeable(sender(), m) =>
      processMessage(m.payload)
      acknowledge(m)
  }

}
