package rs.core.registry

import akka.actor.ActorRef
import rs.core.ServiceKey

object Messages {

  case class Register(serviceKey: ServiceKey, location: ActorRef)

  case class Unregister(serviceKey: ServiceKey, location: ActorRef)

  case class StreamingLookup(serviceKey: ServiceKey)

  case class CancelStreamingLookup(serviceKey: ServiceKey)

  case object CancelAllStreamingLookups

  case class LocationUpdate(serviceKey: ServiceKey, location: Option[ActorRef])

}
