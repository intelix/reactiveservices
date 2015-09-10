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
