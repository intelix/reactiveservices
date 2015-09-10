/*
 * Copyright 2014-15 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rs.core.services

import rs.core.stream.StreamState
import rs.core.{ServiceKey, Subject}

object Messages {


  trait InboundMessage

  trait OutboundMessage

  trait InboundSubjectMessage extends InboundMessage {
    val subj: Subject
  }

  sealed trait ServiceDialect

  sealed trait ServiceInbound extends ServiceDialect with InboundMessage

  sealed trait ServiceOutbound extends ServiceDialect with OutboundMessage

  case class OpenSubscription(subj: Subject, priorityKey: Option[String] = None, aggregationIntervalMs: Int = 0) extends ServiceInbound with InboundSubjectMessage

  case class CloseSubscription(subj: Subject) extends ServiceInbound with InboundSubjectMessage

  case class SubscriptionClosed(subj: Subject) extends ServiceOutbound


  case class InvalidRequest(subj: Subject) extends ServiceOutbound

  case class ServiceNotAvailable(serviceKey: ServiceKey) extends ServiceOutbound

  case class StreamStateUpdate(subject: Subject, topicState: StreamState) extends ServiceOutbound


  case class Signal(subj: Subject, payload: Any, expireAt: Long, orderingGroup: Option[Any], correlationId: Option[Any]) extends Expirable with ServiceInbound with InboundSubjectMessage

//  trait SignalAck extends ServiceOutbound

  case class SignalAckOk(correlationId: Option[Any], subj: Subject, payload: Option[Any]) extends ServiceOutbound

  case class SignalAckFailed(correlationId: Option[Any], subj: Subject, payload: Option[Any]) extends ServiceOutbound

}
