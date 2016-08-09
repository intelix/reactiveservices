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
package au.com.intelix.rs.core.services.endpoint

import akka.actor.ActorRef
import au.com.intelix.rs.core.Subject
import au.com.intelix.rs.core.actors.BaseActor
import au.com.intelix.rs.core.services.Messages._
import au.com.intelix.rs.core.services.endpoint.akkastreams.ServicePortSubscriptionRequestSink
import au.com.intelix.rs.core.services.internal.{DemandProducerContract, StreamAggregatorActor}
import au.com.intelix.rs.core.stream._

import scala.language.postfixOps

trait SubscriptionTerminal
  extends StreamConsumer
    with ServicePortSubscriptionRequestSink
    with StringStreamConsumer
    with DictionaryMapStreamConsumer
    with SetStreamConsumer
    with ListStreamConsumer
    with DemandProducerContract {

  _: BaseActor =>


  override val streamAggregator: ActorRef = context.actorOf(StreamAggregatorActor.props(consumerId = self.path.toStringWithoutAddress), "stream-aggregator")

  override def preStart(): Unit = {
    super.preStart()
    startDemandProducerFor(streamAggregator, withAcknowledgedDelivery = false)
  }


  final def onDemandFulfilled(): Unit = upstreamDemandFulfilled(streamAggregator, 1)

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
