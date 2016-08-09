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
package au.com.intelix.rs.core.registry

import akka.actor.ActorRef
import au.com.intelix.rs.core.actors.BaseActor
import au.com.intelix.evt.TraceE
import au.com.intelix.rs.core.ServiceKey
import au.com.intelix.rs.core.registry.Messages._
import au.com.intelix.rs.core.registry.ServiceRegistryActor.RegistryLocation

import scala.language.postfixOps

object RegistryRef {

  object Evt {
    case object ServiceRegistrationPending extends TraceE
    case object ServiceUnregistrationPending extends TraceE
    case object ServiceLocationUpdate extends TraceE
  }

}

trait RegistryRef extends BaseActor {

  import RegistryRef._

  type LocationHandler = PartialFunction[(ServiceKey, Option[ActorRef]), Unit]

  private var registryRef: Option[ActorRef] = None
  private var pendingToRegistry: List[Any] = List.empty
  private var localLocation: Map[ServiceKey, Option[ActorRef]] = Map.empty
  private var localLocationHandlerFunc: LocationHandler = {
    case _ =>
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[ServiceRegistryActor.RegistryLocation])
    context.system.eventStream.publish(ServiceRegistryActor.RegistryLocationRequest())
    super.preStart()
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    unsubscribeFromRegistryLocation()
    registryRef foreach (_ ! CancelAllStreamingLookups)
    super.postStop()
  }

  private def unsubscribeFromRegistryLocation() = context.system.eventStream.unsubscribe(self, classOf[ServiceRegistryActor.RegistryLocation])

  final def lookupServiceLocation(s: ServiceKey): Option[ActorRef] = localLocation get s flatten

  final def unregisterService(s: ServiceKey) = unregisterServiceAt(s, self)

  final def unregisterServiceAt(s: ServiceKey, loc: ActorRef) = {
    sendToRegistry(Unregister(s, loc))
    raise(Evt.ServiceUnregistrationPending, 'service -> s, 'ref -> loc, 'registry -> registryRef)
  }

  final def registerService(s: ServiceKey) = registerServiceAt(s, self)

  final def registerServiceAt(s: ServiceKey, loc: ActorRef) = {
    sendToRegistry(Register(s, loc))
    raise(Evt.ServiceRegistrationPending, 'service -> s, 'ref -> loc, 'registry -> registryRef)
  }

  final def registerServiceLocationInterest(s: ServiceKey) =
    if (!localLocation.contains(s.id)) {
      sendToRegistry(StreamingLookup(s))
      localLocation += s -> None
    }

  final def unregisterServiceLocationInterest(s: ServiceKey) =
    if (localLocation.contains(s)) {
      sendToRegistry(CancelStreamingLookup(s))
      localLocation -= s.id
    }

  private def sendToRegistry(msg: Any) = registryRef match {
    case Some(r) =>
      if (pendingToRegistry.nonEmpty) sendPending()
      r ! msg
    case None => pendingToRegistry = pendingToRegistry :+ msg
  }

  private def sendPending() = {
    registryRef foreach { rr =>
      pendingToRegistry foreach (rr ! _)
      pendingToRegistry = List.empty
    }
  }

  final def onServiceLocationChanged(handler: LocationHandler) = localLocationHandlerFunc = handler orElse localLocationHandlerFunc

  onMessage {
    case RegistryLocation(ref) =>
      if (registryRef.isEmpty) registryRef = Some(ref)
      sendPending()
      unsubscribeFromRegistryLocation()
    case LocationUpdate(name, maybeLocation) =>
      val was = localLocation.get(name).flatten
      if (was != maybeLocation) {
        localLocation += name -> maybeLocation
        localLocationHandlerFunc((name, maybeLocation))
      }
      raise(Evt.ServiceLocationUpdate, 'service -> name, 'location -> maybeLocation, 'new -> (was != maybeLocation))
  }

}
