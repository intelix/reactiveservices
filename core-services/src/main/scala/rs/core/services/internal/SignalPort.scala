package rs.core.services.internal

import akka.actor.{ActorRef, Props}
import rs.core.actors.{BaseActorSysevents, ActorWithComposableBehavior}
import rs.core.registry.RegistryRef
import rs.core.services.Messages.Signal
import rs.core.services.internal.InternalMessages.SignalPayload
import rs.core.services.internal.SimpleInMemoryAckedDeliveryWithDynamicRouting
import rs.core.sysevents.ref.ComponentWithBaseSysevents

trait SignalPortSysevents extends BaseActorSysevents {
  override def componentId: String = "SignalPort"
}

object SignalPort {
  def props = Props[SignalPort]
}

class SignalPort
  extends ActorWithComposableBehavior
  with SimpleInMemoryAckedDeliveryWithDynamicRouting
  with RegistryRef
  with SignalPortSysevents {

  private var routes: Map[String, ActorRef] = Map.empty

  override def resolveLogicalRoute(routeId: String): Option[ActorRef] = routes get routeId

  onServiceLocationChanged {
    case (key, None) =>
      routes -= key.id
      processQueue()
    case (key, Some(ref)) =>
      routes += key.id -> ref
      processQueue()
  }

  onMessage {
    case Signal(subj, payload, expAt, None, correlationId) =>
      registerServiceLocationInterest(subj.service)
      val signal = SignalPayload(subj, payload, expAt, correlationId)

      unorderedAcknowledgedDelivery(signal, LogicalDestination(subj.service.id))(sender())
    case Signal(subj, payload, expAt, Some(x), correlationId) =>
      registerServiceLocationInterest(subj.service)
      val signal = SignalPayload(subj, payload, expAt, correlationId)
      acknowledgedDelivery(x, signal, LogicalDestination(subj.service.id))(sender())
  }

}