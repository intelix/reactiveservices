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
package rs.core.registry

import akka.actor.ActorRef
import rs.core.ServiceKey
import rs.core.actors.ActorWithComposableBehavior
import rs.core.registry.Messages._
import rs.core.sysevents.ref.ComponentWithBaseSysevents

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


trait RegistryRefSysevents extends ComponentWithBaseSysevents {
  val ServiceRegistered = "ServiceRegistered".trace
  val ServiceUnregistered = "ServiceUnregistered".trace
  val ServiceLocationUpdate = "ServiceLocationUpdate".trace
}

trait RegistryRef extends ActorWithComposableBehavior with RegistryRefSysevents {

  type LocationHandler = PartialFunction[(ServiceKey, Option[ActorRef]), Unit]
  // with healthy config, when registry is deployed on every node, this should be extremely fast lookup, so blocking wait should never be an issue
  private lazy val ref = Await.result(path.resolveOne(5 seconds), 5 seconds)
  private val path = context.actorSelection("/user/local-registry")
  private var localLocation: Map[ServiceKey, Option[ActorRef]] = Map.empty
  private var localLocationHandlerFunc: LocationHandler = {
    case _ =>
  }


  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    ref ! CancelAllStreamingLookups
    super.postStop()
  }

  final def lookupServiceLocation(s: ServiceKey): Option[ActorRef] = localLocation get s flatten

  final def unregisterService(s: ServiceKey) = unregisterServiceAt(s, self)
  final def registerService(s: ServiceKey) = registerServiceAt(s, self)

  final def registerServiceAt(s: ServiceKey, loc: ActorRef) = {
    ref ! Register(s, loc)
    ServiceRegistered('service -> s, 'ref -> loc, 'registry -> ref)
  }
  final def unregisterServiceAt(s: ServiceKey, loc: ActorRef) = {
    ref ! Unregister(s, loc)
    ServiceUnregistered('service -> s, 'ref -> loc, 'registry -> ref)
  }

  final def registerServiceLocationInterest(s: ServiceKey) =
    if (!localLocation.contains(s.id)) {
      ref ! StreamingLookup(s)
      localLocation += s -> None
    }

  final def unregisterServiceLocationInterest(s: ServiceKey) =
    if (localLocation.contains(s)) {
      ref ! CancelStreamingLookup(s)
      localLocation -= s.id
    }

  final def onServiceLocationChanged(handler: LocationHandler) = localLocationHandlerFunc = handler orElse localLocationHandlerFunc

  onMessage {
    case LocationUpdate(name, maybeLocation) =>
      ServiceLocationUpdate { ctx =>
        ctx +('service -> name, 'location -> maybeLocation)
        val was = localLocation.get(name).flatten
        if (was != maybeLocation) {
          ctx + ('new -> true)
          localLocation += name -> maybeLocation
          localLocationHandlerFunc(name, maybeLocation)
        } else ctx + ('new -> false)
      }
  }

}
