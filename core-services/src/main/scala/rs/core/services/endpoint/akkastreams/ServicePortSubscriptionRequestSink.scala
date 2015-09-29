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
import rs.core.actors.SingleStateActor
import rs.core.registry.RegistryRef
import rs.core.services.Messages._
import rs.core.services.internal.StreamAggregatorActor.ServiceLocationChanged
import rs.core.sysevents.ref.ComponentWithBaseSysevents


trait ServicePortSubscriptionRequestSinkSysevents extends ComponentWithBaseSysevents {


  val CompletedSuccessfully = "CompletedSuccessfully".info
  val CompletedWithError = "CompletedWithError".warn
  val TerminatedOnRequest = "TerminatedOnRequest".warn

}


trait ServicePortSubscriptionRequestSink
  extends SingleStateActor
  with RegistryRef
  with ServicePortSubscriptionRequestSinkSysevents {

  val streamAggregator: ActorRef

  private def onServiceUnavailable(key: ServiceKey): Unit = {
    streamAggregator ! ServiceLocationChanged(key, None)
  }

  private def onServiceAvailable(key: ServiceKey, ref: ActorRef): Unit = {
    streamAggregator ! ServiceLocationChanged(key, Some(ref))
  }

  onServiceLocationChanged {
    case (key, None) => onServiceUnavailable(key)
    case (key, Some(ref)) => onServiceAvailable(key, ref)
  }

  def addSubscription(m: OpenSubscription): Unit = {
    val serviceKey = m.subj.service
    streamAggregator ! m
    registerServiceLocationInterest(serviceKey) // this call is idempotent
  }

  def removeSubscription(m: CloseSubscription): Unit = {
    streamAggregator ! m
    // TODO - optimisation - consider closing interest with registry if all requests for the service are closed
  }


}


object ServicePortSubscriptionRequestSinkSubscriber {
  def props(streamAggregator: ActorRef, token: String) = Props(classOf[ServicePortSubscriptionRequestSinkSubscriber], streamAggregator, token)
}

class ServicePortSubscriptionRequestSinkSubscriber(val streamAggregator: ActorRef, token: String)
  extends ServicePortSubscriptionRequestSink
  with ActorSubscriber {

  // TODO config - 5000/1000 make it configurable
  override protected def requestStrategy: RequestStrategy = new WatermarkRequestStrategy(5000, 1000)


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

  onActorTerminated { ref =>
    if (ref == streamAggregator) {
      TerminatedOnRequest()
      terminateInstance()
    }
  }

  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('token -> token)

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

  override def componentId: String = "ServicePort.SubscritionsSink"

}
