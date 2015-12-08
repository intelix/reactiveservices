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
import rs.core.actors.{BaseActor, StatelessActor}
import rs.core.services.internal.InternalMessages.DownstreamDemandRequest
import rs.core.services.internal.acks.Acknowledgeable
import rs.core.sysevents.ref.ComponentWithBaseSysevents

trait StreamDemandBindingEvt extends ComponentWithBaseSysevents {
  val DuplicateDemandRequest = "DuplicateDemandRequest".trace
}

trait StreamDemandBinding extends BaseActor with DuplicateMessageTracker with MessageAcknowledging with StreamDemandBindingEvt {

  def onConsumerDemand(sender: ActorRef, demand: Long)

  private def processDemand(m: DownstreamDemandRequest) = if (isNotDuplicate(sender(), "DownstreamDemandRequest", m.messageId)) {
    onConsumerDemand(sender(), m.count)
  } else {
    DuplicateDemandRequest('sender -> sender(), 'payload -> m)
  }

  onMessage {
    case m: DownstreamDemandRequest => processDemand(m)
  }

  override def shouldProcessAcknowledgeable(sender: ActorRef, m: Acknowledgeable): Boolean = {
    m.payload match {
      case m: DownstreamDemandRequest => true
      case _ => super.shouldProcessAcknowledgeable(sender, m)
    }
  }
}
