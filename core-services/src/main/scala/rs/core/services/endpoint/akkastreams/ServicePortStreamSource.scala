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
package rs.core.services.endpoint.akkastreams

import akka.actor.{ActorRef, PoisonPill, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import rs.core.actors.StatelessActor
import rs.core.services.Messages.ServiceOutbound
import rs.core.services.SequentialMessageIdGenerator
import rs.core.services.internal.InternalMessages.DownstreamDemandRequest
import rs.core.sysevents.CommonEvt


trait ServicePortStreamSourceEvt extends CommonEvt {

  val Cancelled = "Cancelled".info
  val TerminatingOnRequest = "TerminatingOnRequest".info
  val DemandProduced = "DemandProduced".trace
  val OnNext = "OnNext".trace

  override def componentId: String = "ServicePort.StreamDataSource"
}

object ServicePortStreamSource {
  def props(streamAggregator: ActorRef, token: String) = Props(classOf[ServicePortStreamSource], streamAggregator, token)
}

class ServicePortStreamSource(streamAggregator: ActorRef, token: String)
  extends StatelessActor
    with ActorPublisher[Any]
    with ServicePortStreamSourceEvt {

  private val messageIdGenerator = new SequentialMessageIdGenerator()

  onMessage {
    case Request(n) =>
//      println(s"!>>> Requested demand: $n")
      DemandProduced('new -> n, 'total -> totalDemand)
      streamAggregator ! DownstreamDemandRequest(messageIdGenerator.next(), n)
    case Cancel =>
      Cancelled()
      streamAggregator ! PoisonPill
      context.stop(self)
    case m: ServiceOutbound =>
//      println(s"!>>> Produced: $m")
      OnNext('demand -> totalDemand)
      onNext(m)
  }

  onActorTerminated { ref =>
    if (ref == streamAggregator) {
      TerminatingOnRequest()
      onCompleteThenStop()
    }
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(streamAggregator)
  }

  addEvtFields('token -> token)

}
