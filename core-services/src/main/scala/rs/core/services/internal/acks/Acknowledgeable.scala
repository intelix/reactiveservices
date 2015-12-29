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

