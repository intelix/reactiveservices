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
package au.com.intelix.rs.core.services.endpoint.akkastreams

import akka.actor.{ActorRef, PoisonPill, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import au.com.intelix.evt.{InfoE, TraceE}
import au.com.intelix.rs.core.actors.StatelessActor
import au.com.intelix.rs.core.services.Messages.ServiceOutbound
import au.com.intelix.rs.core.services.SequentialMessageIdGenerator
import au.com.intelix.rs.core.services.internal.InternalMessages.DownstreamDemandRequest


object ServicePortStreamSource {

  object Evt {
    case object Cancelled extends InfoE

    case object TerminatingOnRequest extends InfoE

    case object DemandProduced extends TraceE

    case object OnNext extends TraceE
  }

  def props(streamAggregator: ActorRef, token: String) = Props(classOf[ServicePortStreamSource], streamAggregator, token)
}

class ServicePortStreamSource(streamAggregator: ActorRef, token: String) extends StatelessActor with ActorPublisher[Any] {

  import ServicePortStreamSource._

  private val messageIdGenerator = new SequentialMessageIdGenerator()

  onMessage {
    case Request(n) =>
      raise(Evt.DemandProduced, 'new -> n, 'total -> totalDemand)
      streamAggregator ! DownstreamDemandRequest(messageIdGenerator.next(), n)
    case Cancel =>
      raise(Evt.Cancelled)
      streamAggregator ! PoisonPill
      context.stop(self)
    case m: ServiceOutbound =>
      raise(Evt.OnNext, 'demand -> totalDemand)
      onNext(m)
  }

  onActorTerminated { ref =>
    if (ref == streamAggregator) {
      raise(Evt.TerminatingOnRequest)
      onCompleteThenStop()
    }
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(streamAggregator)
  }

  commonEvtFields('token -> token)

}
