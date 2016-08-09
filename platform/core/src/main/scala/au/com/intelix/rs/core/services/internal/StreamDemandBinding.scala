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
package au.com.intelix.rs.core.services.internal

import akka.actor.ActorRef
import au.com.intelix.rs.core.actors.{BaseActor, StatelessActor}
import au.com.intelix.evt.{CommonEvt, TraceE}
import au.com.intelix.rs.core.services.internal.InternalMessages.DownstreamDemandRequest
import au.com.intelix.rs.core.services.internal.acks.Acknowledgeable

object StreamDemandBinding {
  object Evt {
    case object DuplicateDemandRequest extends TraceE
  }
}

trait StreamDemandBinding extends BaseActor with DuplicateMessageTracker with MessageAcknowledging {
  def onConsumerDemand(sender: ActorRef, demand: Long)

  private def processDemand(m: DownstreamDemandRequest) =
    if (isNotDuplicate(sender(), "DownstreamDemandRequest", m.messageId)) {
      onConsumerDemand(sender(), m.count)
    } else {
      raise(StreamDemandBinding.Evt.DuplicateDemandRequest, 'sender -> sender(), 'payload -> m)
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
