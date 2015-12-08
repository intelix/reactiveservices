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
package rs.core.services.endpoint.akkastreams

import akka.actor.{ActorRef, PoisonPill, Props}
import akka.stream.actor.ActorSubscriberMessage.{OnComplete, OnError, OnNext}
import akka.stream.actor.{ActorSubscriber, RequestStrategy, WatermarkRequestStrategy}
import rs.core.ServiceKey
import rs.core.actors.StatelessActor
import rs.core.config.ConfigOps.wrap
import rs.core.registry.RegistryRef
import rs.core.services.Messages._
import rs.core.services.internal.StreamAggregatorActor.ServiceLocationChanged
import rs.core.sysevents.ref.ComponentWithBaseSysevents


trait ServicePortSubscriptionRequestSinkEvt extends ComponentWithBaseSysevents {
  val CompletedSuccessfully = "CompletedSuccessfully".info
  val CompletedWithError = "CompletedWithError".warn
  val TerminatedOnRequest = "TerminatedOnRequest".warn

  override def componentId: String = "ServicePort.SubscriptionSink"
}


trait ServicePortSubscriptionRequestSink
  extends StatelessActor
    with RegistryRef
    with ServicePortSubscriptionRequestSinkEvt {

  val streamAggregator: ActorRef

  def addSubscription(m: OpenSubscription): Unit = {
    val serviceKey = m.subj.service
    streamAggregator ! m
    registerServiceLocationInterest(serviceKey) // this call is idempotent
  }

  def removeSubscription(m: CloseSubscription): Unit = streamAggregator ! m

  onServiceLocationChanged {
    case (key, None) => onServiceUnavailable(key)
    case (key, Some(ref)) => onServiceAvailable(key, ref)
  }

  private def onServiceUnavailable(key: ServiceKey): Unit = {
    streamAggregator ! ServiceLocationChanged(key, None)
  }

  private def onServiceAvailable(key: ServiceKey, ref: ActorRef): Unit = {
    streamAggregator ! ServiceLocationChanged(key, Some(ref))
  }


}


object ServicePortSubscriptionRequestSinkSubscriber {
  def props(streamAggregator: ActorRef, token: String) = Props(classOf[ServicePortSubscriptionRequestSinkSubscriber], streamAggregator, token)
}

class ServicePortSubscriptionRequestSinkSubscriber(val streamAggregator: ActorRef, token: String)
  extends ServicePortSubscriptionRequestSink
    with ActorSubscriber {

  val HighWatermark = nodeCfg.asInt("service-port.backpressure.high-watermark", 5000)
  val LowWatermark = nodeCfg.asInt("service-port.backpressure.low-watermark", 1000)

  def terminateInstance(): Unit = {
    // for some reason streams 1.0 don't terminate the actor automatically and it's not happening when cancel() is called as it is already marked
    // as 'cancelled', as OnComplete and OnError are processed in aroundReceive
    try {
      cancel()
    } catch {
      case _: Throwable =>
    }
    context.stop(self)
  }


  onMessage {
    case OnNext(m: OpenSubscription) => addSubscription(m)
    case OnNext(m: CloseSubscription) => removeSubscription(m)
    case OnComplete =>
      CompletedSuccessfully()
      terminateInstance()
    case OnError(x) =>
      CompletedWithError('error -> x)
      terminateInstance()
  }

  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('token -> token)

  onActorTerminated { ref =>
    if (ref == streamAggregator) {
      TerminatedOnRequest()
      terminateInstance()
    }
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(streamAggregator)
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    streamAggregator ! PoisonPill
    super.postStop()
  }

  override protected def requestStrategy: RequestStrategy = new WatermarkRequestStrategy(HighWatermark, LowWatermark)


}
