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
package rs.core.services.endpoint

import akka.actor.ActorRef
import akka.pattern.Patterns
import rs.core.Subject
import rs.core.actors.StatefulActor
import rs.core.services.Messages._
import rs.core.services.endpoint.akkastreams.ServicePortSubscriptionRequestSink
import rs.core.services.internal.{DemandProducerContract, SignalPort, StreamAggregatorActor}
import rs.core.stream._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

trait Terminal
  extends StreamConsumer
  with ServicePortSubscriptionRequestSink
  with StringStreamConsumer
  with DictionaryMapStreamConsumer
  with SetStreamConsumer
  with ListStreamConsumer
  with DemandProducerContract {

  _: StatefulActor[_] =>


  override val streamAggregator: ActorRef = context.actorOf(StreamAggregatorActor.props(consumerId = componentId), "stream-aggregator")

  startDemandProducerFor(streamAggregator, withAcknowledgedDelivery = false)


  override def preStart(): Unit = {
    super.preStart()
    startDemandProducerFor(streamAggregator, withAcknowledgedDelivery = false)
  }

  val signalPort: ActorRef = context.actorOf(SignalPort.props, "signal-port")


  final def onDemandFulfilled(): Unit = upstreamDemandFulfilled(streamAggregator, 1)

  final def signal(subj: Subject, payload: Any = None, expiry: FiniteDuration = 1 minute, orderingGroup: Option[Any] = None, correlationId: Option[Any] = None) = {
    signalPort ! Signal(subj, payload, now + expiry.toMillis, orderingGroup, correlationId)
  }

  final def signalAsk(subj: Subject, payload: Any = None, expiry: FiniteDuration = 1 minute, orderingGroup: Option[Any] = None, correlationId: Option[Any] = None) =
    Patterns.ask(signalPort, Signal(subj, payload, now + expiry.toMillis, orderingGroup, correlationId), expiry.toMillis)

  final def subscribe(subj: Subject, priorityKey: Option[String] = None, aggregationIntervalMs: Int = 0) = {
    addSubscription(OpenSubscription(subj, priorityKey, aggregationIntervalMs))
  }

  final def unsubscribe(subj: Subject) = {
    removeSubscription(CloseSubscription(subj))
  }


  onMessage {
    case StreamStateUpdate(subject, state) =>
      onDemandFulfilled()
      update(subject, state)
    case ServiceNotAvailable(service) =>
      processServiceNotAvailable(service)
  }
}
