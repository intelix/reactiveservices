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

import akka.actor._
import rs.core.ServiceKey
import rs.core.actors.{BaseActorSysevents, ActorWithComposableBehavior}
import rs.core.config.ConfigOps.wrap
import rs.core.registry.Messages._
import rs.core.registry.ServiceRegistryActor.{RegistryLocation, RegistryLocationRequest}
import rs.core.sysevents.ref.ComponentWithBaseSysevents

trait ServiceRegistrySysevents extends BaseActorSysevents {

  val ServiceRegistered = "ServiceRegistered".info
  val ServiceUnregistered = "ServiceUnregistered".info

  override def componentId: String = "ServiceRegistry"
}

object ServiceRegistrySysevents extends ServiceRegistrySysevents


object ServiceRegistryActor {

  case class RegistryLocation(ref: ActorRef)

  case class RegistryLocationRequest()

}

class ServiceRegistryActor(id: String)
  extends ActorWithComposableBehavior
  with ServiceRegistrySysevents {

  private var services: Map[ServiceKey, ActorRef] = Map.empty
  private var interests: Map[ServiceKey, Set[ActorRef]] = Map.empty

  context.system.eventStream.subscribe(self, classOf[RegistryLocationRequest])

  val nodeId = config.asString("node.id", "n/a")
  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('service -> id, 'nodeid -> nodeId)

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    publishOurLocation()
  }

  private def publishOurLocation() = context.system.eventStream.publish(RegistryLocation(self))


  private def updateStream(serviceKey: ServiceKey, location: Option[ActorRef]): Unit = {
    val update = LocationUpdate(serviceKey, location)
    interests get serviceKey foreach (_.foreach(_ ! update))
  }

  private def addService(serviceKey: ServiceKey, location: ActorRef): Unit =
    ServiceRegistered { ctx =>
      ctx +('key -> serviceKey, 'ref -> location)
      services get serviceKey match {
        case Some(r) if r == location =>
          ctx + ('new -> false)
        case _ =>
          ctx + ('new -> true)
          services += serviceKey -> context.watch(location)
          updateStream(serviceKey, Some(location))
      }
    }


  private def addInterest(ref: ActorRef, serviceKey: ServiceKey): Unit = {
    interests += (serviceKey -> (interests.getOrElse(serviceKey, Set.empty) + context.watch(ref)))
    ref ! LocationUpdate(serviceKey, services get serviceKey)
  }

  private def removeInterest(ref: ActorRef, serviceKey: ServiceKey): Unit = {
    interests = (interests get serviceKey).map(_ - ref) match {
      case Some(x) if x.nonEmpty => interests + (serviceKey -> x)
      case _ => interests - serviceKey
    }
  }

  def removeAllInterests(ref: ActorRef): Unit = {
    context.unwatch(ref)
    interests = interests.map {
      case (k, v) if v.contains(ref) => k -> (v - ref)
      case x => x
    } filter (_._2.nonEmpty)
  }

  onMessage {
    case Register(serviceKey, location) => addService(serviceKey, location)
    case Unregister(serviceKey, location) => removeService(serviceKey, location)
    case StreamingLookup(serviceKey) => addInterest(sender(), serviceKey)
    case CancelStreamingLookup(serviceKey) => removeInterest(sender(), serviceKey)
    case CancelAllStreamingLookups => removeAllInterests(sender())
    case RegistryLocationRequest() => publishOurLocation()
  }

  private def removeService(serviceKey: ServiceKey, ref: ActorRef): Unit = ServiceUnregistered { ctx =>
    ctx +('key -> serviceKey, 'ref -> ref, 'reason -> "request")
    if (services.get(serviceKey) contains ref) {
      services -= serviceKey
      updateStream(serviceKey, None)
    }
  }

  private def removeService(ref: ActorRef): Unit =
    services = services filter {
      case (k, v) =>
        if (v == ref) {
          ServiceUnregistered('key -> k, 'ref -> ref, 'reason -> "termination")
          updateStream(k, None)
          false
        } else true
    }


  onActorTerminated { ref =>
    removeService(ref)
    removeAllInterests(ref)
  }

}


