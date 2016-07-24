/*
 * Copyright 2014-16 Intelix Pty Ltd
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
package au.com.intelix.rs.core.codec.binary

import au.com.intelix.rs.core.{ServiceKey, Subject}
import au.com.intelix.rs.core.services.Expirable
import au.com.intelix.rs.core.services.Messages.{InboundMessage, OutboundMessage}
import au.com.intelix.rs.core.stream.{StreamState, StreamStateTransition}

object BinaryProtocolMessages {

  sealed trait BinaryDialect

  sealed trait BinaryDialectInbound extends BinaryDialect with InboundMessage

  sealed trait BinaryDialectOutbound extends BinaryDialect with OutboundMessage

  trait HighPriority


  case class BinaryDialectAlias(id: Int, subj: Subject) extends BinaryDialectInbound

  case class BinaryDialectPing(id: Int) extends BinaryDialectOutbound

  case class BinaryDialectPong(id: Int) extends BinaryDialectInbound


  case class BinaryDialectServiceNotAvailable(serviceKey: ServiceKey) extends BinaryDialectOutbound

  case class BinaryDialectInvalidRequest(subjAlias: Int) extends BinaryDialectOutbound


  case class BinaryDialectOpenSubscription(subjAlias: Int, priorityKey: Option[String] = None, aggregationIntervalMs: Int = 0) extends BinaryDialectInbound

  case class BinaryDialectCloseSubscription(subjAlias: Int) extends BinaryDialectInbound

  case class BinaryDialectSubscriptionClosed(subjAlias: Int) extends BinaryDialectOutbound

  case class BinaryDialectResetSubscription(subjAlias: Int) extends BinaryDialectInbound


  case class BinaryDialectStreamStateUpdate(subjAlias: Int, topicState: StreamState) extends BinaryDialectOutbound

  case class BinaryDialectStreamStateTransitionUpdate(subjAlias: Int, topicStateTransition: StreamStateTransition) extends BinaryDialectOutbound


  case class BinaryDialectSignal(subjAlias: Int, payload: Any, expireAt: Long, orderingGroup: Option[Any], correlationId: Option[Any]) extends Expirable with BinaryDialectInbound

  case class BinaryDialectSignalAckOk(subjAlias: Int, correlationId: Option[Any], payload: Option[Any]) extends BinaryDialectOutbound

  case class BinaryDialectSignalAckFailed(subjAlias: Int, correlationId: Option[Any], payload: Option[Any]) extends BinaryDialectOutbound


}
