package rs.core.services.internal.acks

import akka.actor.ActorRef
import rs.core.services.{MessageId, RandomStringMessageId, WithMessageId}

object Acknowledgeable {
  def apply(m: Any, acknowledgeTo: Option[ActorRef]) = m match {
    case x: WithMessageId => AcknowledgeableWithDerivedId(x, acknowledgeTo)
    case x => AcknowledgeableWithSpecificId(x, acknowledgeTo, RandomStringMessageId())
  }

  def apply(messageId: MessageId, m: Any, acknowledgeTo: Option[ActorRef]) = AcknowledgeableWithSpecificId(m, acknowledgeTo, messageId)

}

trait Acknowledgeable {
  def messageId: MessageId

  def payload: Any

  def acknowledgeTo: Option[ActorRef]
}

case class AcknowledgeableWithSpecificId(payload: Any, acknowledgeTo: Option[ActorRef], messageId: MessageId) extends Acknowledgeable

case class AcknowledgeableWithDerivedId(payload: WithMessageId, acknowledgeTo: Option[ActorRef]) extends Acknowledgeable {
  override def messageId: MessageId = payload.messageId
}

