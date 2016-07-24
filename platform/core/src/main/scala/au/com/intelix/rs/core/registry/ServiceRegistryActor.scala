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

import akka.actor._
import au.com.intelix.rs.core.actors.StatelessActor
import au.com.intelix.evt.{EvtSource, InfoE}
import au.com.intelix.rs.core.ServiceKey
import au.com.intelix.rs.core.registry.Messages._

object ServiceRegistryActor {

  val EvtSourceId = "ServiceRegistry"

  case object EvtServiceRegistered extends InfoE

  case object EvtServiceUnregistered extends InfoE

  case object EvtPublishingNewServiceLocation extends InfoE

  case class RegistryLocation(ref: ActorRef)

  case class RegistryLocationRequest()

}

class ServiceRegistryActor
  extends StatelessActor {

  import ServiceRegistryActor._

  private var services: Map[ServiceKey, List[ActorRef]] = Map.empty
  private var interests: Map[ServiceKey, Set[ActorRef]] = Map.empty

  context.system.eventStream.subscribe(self, classOf[RegistryLocationRequest])

  addEvtFields('service -> self.path.name)

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    publishOurLocation()
  }

  private def publishOurLocation() = context.system.eventStream.publish(RegistryLocation(self))


  private def updateStream(serviceKey: ServiceKey, location: Option[ActorRef]): Unit = {
    val update = LocationUpdate(serviceKey, location)
    interests get serviceKey foreach (_.foreach(_ ! update))
    raise(EvtPublishingNewServiceLocation, 'key -> serviceKey, 'location -> location)
  }

  private def addService(serviceKey: ServiceKey, location: ActorRef): Unit =
    services get serviceKey match {
      case Some(list) if list contains location =>
        raise(EvtServiceRegistered, 'key -> serviceKey, 'ref -> location, 'new -> false)
      case Some(list) =>
        raise(EvtServiceRegistered, 'key -> serviceKey, 'ref -> location, 'new -> true, 'locations -> (list.size + 1))
        services += serviceKey -> (list :+ context.watch(location))
      case _ =>
        raise(EvtServiceRegistered, 'key -> serviceKey, 'ref -> location, 'new -> true, 'locations -> 1)
        services += serviceKey -> List(context.watch(location))
        updateStream(serviceKey, Some(location))
    }

  private def addInterest(ref: ActorRef, serviceKey: ServiceKey): Unit = {
    interests += (serviceKey -> (interests.getOrElse(serviceKey, Set.empty) + context.watch(ref)))
    ref ! LocationUpdate(serviceKey, (services get serviceKey).flatMap(_.headOption))
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

  private def removeService(serviceKey: ServiceKey, ref: ActorRef): Unit =
    services.get(serviceKey) match {
      case Some(l) if l.contains(ref) =>
        val currentHead = l.head
        val newList = l filter (_ != ref)
        if (!newList.headOption.contains(currentHead)) updateStream(serviceKey, newList.headOption)
        if (newList.nonEmpty)
          services += (serviceKey -> newList)
        else
          services -= serviceKey
        raise(EvtServiceUnregistered, 'key -> serviceKey, 'ref -> ref, 'reason -> "request", 'remainingLocations -> newList.size)
      case _ =>
    }

  private def removeService(ref: ActorRef): Unit =
    services foreach {
      case (k, v) =>
        if (v.contains(ref)) {
          val currentHead = v.head
          val newList = v filter (_ != ref)
          if (!newList.headOption.contains(currentHead)) updateStream(k, newList.headOption)
          if (newList.nonEmpty)
            services += (k -> newList)
          else
            services -= k
          raise(EvtServiceUnregistered, 'key -> k, 'ref -> ref, 'reason -> "termination", 'remainingLocations -> newList.size)
        }
    }

  onActorTerminated { ref =>
    removeService(ref)
    removeAllInterests(ref)
  }
  override val evtSource: EvtSource = EvtSourceId
}


