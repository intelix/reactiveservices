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
package rs.core.codec.binary

import rs.core.services.Expirable
import rs.core.services.Messages.{InboundMessage, OutboundMessage}
import rs.core.stream.{StreamState, StreamStateTransition}
import rs.core.{ServiceKey, Subject}

object BinaryProtocolMessages {

  sealed trait BinaryDialectMessage

  sealed trait BinaryDialectInboundMessage extends BinaryDialectMessage with InboundMessage

  sealed trait BinaryDialectOutboundMessage extends BinaryDialectMessage with OutboundMessage



  case class BinaryDialectAlias(id: Int, subj: Subject) extends BinaryDialectInboundMessage

  case class BinaryDialectPing(id: Int) extends BinaryDialectOutboundMessage

  case class BinaryDialectPong(id: Int) extends BinaryDialectInboundMessage


  case class BinaryDialectServiceNotAvailable(serviceKey: ServiceKey) extends BinaryDialectOutboundMessage

  case class BinaryDialectInvalidRequest(subjAlias: Int) extends BinaryDialectOutboundMessage


  case class BinaryDialectOpenSubscription(subjAlias: Int, priorityKey: Option[String] = None, aggregationIntervalMs: Int = 0) extends BinaryDialectInboundMessage

  case class BinaryDialectCloseSubscription(subjAlias: Int) extends BinaryDialectInboundMessage

  case class BinaryDialectSubscriptionClosed(subjAlias: Int) extends BinaryDialectOutboundMessage

  case class BinaryDialectResetSubscription(subjAlias: Int) extends BinaryDialectInboundMessage


  case class BinaryDialectStreamStateUpdate(subjAlias: Int, topicState: StreamState) extends BinaryDialectOutboundMessage

  case class BinaryDialectStreamStateTransitionUpdate(subjAlias: Int, topicStateTransition: StreamStateTransition) extends BinaryDialectOutboundMessage


  case class BinaryDialectSignal(subjAlias: Int, payload: Any, expireAt: Long, orderingGroup: Option[Any], correlationId: Option[Any]) extends Expirable with BinaryDialectInboundMessage

  case class BinaryDialectSignalAckOk(subjAlias: Int, correlationId: Option[Any], payload: Option[Any]) extends BinaryDialectOutboundMessage

  case class BinaryDialectSignalAckFailed(subjAlias: Int, correlationId: Option[Any], payload: Option[Any]) extends BinaryDialectOutboundMessage


}
