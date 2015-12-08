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
package rs.core.services.internal

import akka.actor.{ActorRef, Props}
import rs.core.actors.{CommonActorEvt, StatelessActor}
import rs.core.registry.RegistryRef
import rs.core.services.Messages.Signal
import rs.core.services.internal.InternalMessages.SignalPayload

trait SignalPortEvt extends CommonActorEvt {
  override def componentId: String = "SignalPort"
}

object SignalPort {
  def props = Props[SignalPort]
}

class SignalPort
  extends StatelessActor
    with SimpleInMemoryAckedDeliveryWithDynamicRouting
    with RegistryRef
    with SignalPortEvt {

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