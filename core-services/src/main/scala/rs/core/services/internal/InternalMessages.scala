package rs.core.services.internal

import rs.core.Subject
import rs.core.services.{StreamId, Expirable, MessageId}
import rs.core.stream.StreamStateTransition

object InternalMessages {

  case class DownstreamDemandRequest(messageId: MessageId, count: Long)

  case class SignalPayload(subj: Subject, payload: Any, expireAt: Long, correlationId: Option[Any]) extends Expirable

  case class StreamUpdate(key: StreamId, tran: StreamStateTransition)

}
