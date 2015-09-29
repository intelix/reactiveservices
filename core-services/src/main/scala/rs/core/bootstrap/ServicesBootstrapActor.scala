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
package rs.core.bootstrap

import akka.actor.Props
import rs.core.ServiceKey
import rs.core.actors.{WithGlobalConfig, SingleStateActor, BaseActorSysevents}
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService

import scala.collection.JavaConversions

trait ServicesBootstrapEvents extends BaseActorSysevents {

  val StartingService = "StartingService".trace

  override def componentId: String = "ServiceBootstrap"
}

object ServicesBootstrapEvents extends ServicesBootstrapEvents

object ServicesBootstrapActor {
  case class ForwardToService(id: String, m: Any)
}
class ServicesBootstrapActor extends SingleStateActor with ServicesBootstrapEvents with WithGlobalConfig {

  case class ServiceMeta(id: String, cl: String)

  lazy val services: List[ServiceMeta] = JavaConversions.asScalaSet(config.getConfig("node.services").entrySet()).map {
    case e => ServiceMeta(e.getKey, e.getValue.unwrapped().toString)
  }.toList

  var servicesCounter = 0

  private def startProvider(sm: ServiceMeta) = StartingService { ctx =>
    val actor = context.actorOf(Props(Class.forName(sm.cl), sm.id), sm.id)
    ctx +('service -> sm.id, 'class -> sm.cl, 'ref -> actor)
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    services foreach startProvider
  }

  onMessage {
    case ForwardToService(id, m) => context.actorSelection(id).forward(m)
  }
}
