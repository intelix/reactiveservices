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
package rs.core.services.endpoint

import akka.actor.ActorRef
import rs.core.actors.ActorWithComposableBehavior
import rs.core.stream._
import rs.core.services.internal.{StreamAggregatorActor, DemandProducerContract}

trait Terminal
  extends ActorWithComposableBehavior
  with StreamConsumer
  with StringStreamConsumer
  with CustomStreamConsumer
  with DictionaryMapStreamConsumer
  with SetStreamConsumer
  with ListStreamConsumer
  with DemandProducerContract {


  override val streamAggregator: ActorRef = context.actorOf(StreamAggregatorActor.props(componentId), "stream-aggregator")

  startDemandProducerFor(streamAggregator, withAcknowledgedDelivery = false)

  override def preStart(): Unit = {
    super.preStart()
    startDemandProducerFor(streamAggregator, withAcknowledgedDelivery = false)
  }

  final override def onDemandFulfilled(): Unit = upstreamDemandFulfilled(streamAggregator, 1)
}
