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
package rs.core.services.internal

import rs.core.{Ser, Subject}
import rs.core.services.{StreamId, Expirable, MessageId}
import rs.core.stream.StreamStateTransition

private[services] object InternalMessages {

  case class DownstreamDemandRequest(messageId: MessageId, count: Long) extends Ser

  case class SignalPayload(subj: Subject, payload: Any, expireAt: Long, correlationId: Option[Any]) extends Expirable with Ser

  case class StreamUpdate(key: StreamId, tran: StreamStateTransition) extends Ser

}
