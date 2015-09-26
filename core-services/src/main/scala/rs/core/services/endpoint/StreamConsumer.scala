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
import akka.pattern.Patterns
import rs.core.actors.BasicActor
import rs.core.services.Messages._
import rs.core.services.endpoint.akkastreams.ServicePortSubscriptionRequestSink
import rs.core.services.internal.SignalPort
import rs.core.stream.StreamState
import rs.core.{ServiceKey, Subject}

import scala.concurrent.duration._
import scala.language.postfixOps


trait StreamConsumer extends BasicActor with ServicePortSubscriptionRequestSink {

  type StreamUpdateHandler = PartialFunction[(Subject, StreamState), Unit]
  type ServiceKeyEventHandler = PartialFunction[ServiceKey, Unit]

  val HighestPriority = Some("A")

  private var missingServices: Set[ServiceKey] = Set.empty

  private var streamUpdateHandlerFunc: StreamUpdateHandler = {
    case _ =>
  }
  private var serviceNotAvailableHandlerFunc: ServiceKeyEventHandler = {
    case _ =>
  }
  private var serviceAvailableHandlerFunc: ServiceKeyEventHandler = {
    case _ =>
  }

  val signalPort: ActorRef = context.actorOf(SignalPort.props, "signal-port")

  def onStreamUpdate(handler: StreamUpdateHandler) = streamUpdateHandlerFunc = handler orElse streamUpdateHandlerFunc

  def onServiceNotAvailable(handler: ServiceKeyEventHandler) = serviceNotAvailableHandlerFunc = handler orElse serviceNotAvailableHandlerFunc

  def onServiceAvailable(handler: ServiceKeyEventHandler) = serviceAvailableHandlerFunc = handler orElse serviceAvailableHandlerFunc

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

  private def update(subj: Subject, tran: StreamState) = {
    if (missingServices.contains(subj.service)) {
      missingServices -= subj.service
      serviceAvailableHandlerFunc(subj.service)
    }
    streamUpdateHandlerFunc(subj, tran)
  }

  private def processServiceNotAvailable(service: ServiceKey): Unit =
    if (!missingServices.contains(service)) {
      missingServices += service
      serviceNotAvailableHandlerFunc(service)
    }

  def onDemandFulfilled()

  onMessage {
    case StreamStateUpdate(subject, state) =>
      onDemandFulfilled()
      update(subject, state)
    case ServiceNotAvailable(service) =>
      processServiceNotAvailable(service)
  }

}
