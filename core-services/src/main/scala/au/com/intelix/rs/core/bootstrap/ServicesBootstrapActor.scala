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
package au.com.intelix.rs.core.bootstrap

import akka.actor.Props
import au.com.intelix.rs.core.actors.StatelessActor
import au.com.intelix.evt.{EvtSource, InfoE}

import scala.collection.JavaConversions

object ServicesBootstrapActor {

  val EvtSourceId = "ServiceBootstrap"

  case object EvtStartingService extends InfoE

  case class ForwardToService(id: String, m: Any)

}

class ServicesBootstrapActor extends StatelessActor {

  import ServicesBootstrapActor._

  case class ServiceMeta(id: String, cl: String)

  lazy val services: List[ServiceMeta] = JavaConversions.asScalaSet(config.getConfig("node.services").entrySet()).map {
    case e => ServiceMeta(e.getKey, e.getValue.unwrapped().toString)
  }.toList

  var servicesCounter = 0

  private def startProvider(sm: ServiceMeta) = {
    val actor = context.actorOf(Props(Class.forName(sm.cl), sm.id), sm.id)
    raise(EvtStartingService, 'service -> sm.id, 'class -> sm.cl, 'ref -> actor)
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    services foreach startProvider
  }

  onMessage {
    case ForwardToService(id, m) => context.actorSelection(id).forward(m)
  }
  override val evtSource: EvtSource = EvtSourceId
}
