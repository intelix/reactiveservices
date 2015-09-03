package rs.core.services

import rs.core.stream.{StreamState, StreamStateTransition}
import rs.core.services.internal.StreamRef$
import rs.core.{ServiceKey, Subject}

object Messages {


  trait InboundMessage
  trait OutboundMessage
  trait InboundSubjectMessage extends InboundMessage {
    val subj: Subject
  }

  sealed trait ServiceDialectMessage

  sealed trait ServiceInboundMessage extends ServiceDialectMessage with InboundMessage
  sealed trait ServiceOutboundMessage extends ServiceDialectMessage with OutboundMessage

  case class OpenSubscription(subj: Subject, priorityKey: Option[String] = None, aggregationIntervalMs: Int = 0) extends ServiceInboundMessage with InboundSubjectMessage

  case class CloseSubscription(subj: Subject) extends ServiceInboundMessage with InboundSubjectMessage

  case class SubscriptionClosed(subj: Subject) extends ServiceOutboundMessage


  case class InvalidRequest(subj: Subject) extends ServiceOutboundMessage

  case class ServiceNotAvailable(serviceKey: ServiceKey) extends ServiceOutboundMessage

  case class StreamStateUpdate(subject: Subject, topicState: StreamState) extends ServiceOutboundMessage



  case class Signal(subj: Subject, payload: Any, expireAt: Long, orderingGroup: Option[Any], correlationId: Option[Any]) extends Expirable with ServiceInboundMessage with InboundSubjectMessage

  trait SignalAck extends ServiceOutboundMessage

  case class SignalAckOk(correlationId: Option[Any], subj: Subject, payload: Option[Any]) extends SignalAck

  case class SignalAckFailed(correlationId: Option[Any], subj: Subject, payload: Option[Any]) extends SignalAck

}
