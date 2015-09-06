package rs.core.registry

import akka.actor._
import rs.core.ServiceKey
import rs.core.actors.ActorWithComposableBehavior
import rs.core.registry.Messages._
import rs.core.sysevents.ref.ComponentWithBaseSysevents

trait ServiceRegistrySysevents extends ComponentWithBaseSysevents {

  val ServiceRegistered = "ServiceRegistered".info
  val ServiceUnregistered = "ServiceUnregistered".info

  override def componentId: String = "ServiceRegistry"
}


object ServiceRegistryActor {

  def start(implicit f: ActorSystem) = f.actorOf(Props[ServiceRegistryActor], "local-registry")

}


class ServiceRegistryActor
  extends ActorWithComposableBehavior
  with ServiceRegistrySysevents {

  private var services: Map[ServiceKey, ActorRef] = Map.empty
  private var interests: Map[ServiceKey, Set[ActorRef]] = Map.empty


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


