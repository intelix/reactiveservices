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
package rs.node.core

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor._
import com.typesafe.config._
import com.typesafe.scalalogging.StrictLogging
import net.ceedubs.ficus.Ficus._
import rs.core.actors.{CommonActorEvt, StatelessActor}
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService
import rs.core.config.ConfigOps.wrap
import rs.core.config.NodeConfig
import rs.core.sysevents.{CommonEvt, EvtPublisherContext, EvtPublisherContext$}
import rs.node.core.ServiceClusterGuardianActor.RestartRequestException

import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.Scalaz._

trait ServiceClusterGuardianActorEvt extends CommonEvt with CommonActorEvt {

  val Launched = "Launched".info

  override def componentId: String = "Cluster.Guardian"

}

object ServiceClusterGuardianActorEvt extends ServiceClusterGuardianActorEvt

object ServiceClusterGuardianActor {

  class RestartRequestException extends Exception

  def props(config: NodeConfig) = Props(new ServiceClusterGuardianActor(config))

  def start(config: NodeConfig)(implicit f: ActorRefFactory) = f.actorOf(props(config))
}

class ServiceClusterGuardianActor(cfg: NodeConfig)
  extends StatelessActor
    with ServiceClusterGuardianActorEvt
    with StrictLogging {


  override implicit lazy val nodeCfg: NodeConfig = cfg

  private val maxRetries = nodeCfg.asInt("node.cluster.max-retries", -1)
  private val maxRetriesTimewindow = nodeCfg.asOptFiniteDuration("node.cluster.max-retries-window") match {
    case Some(d) => d
    case None => Duration.Inf
  }

  private var clusterSystem: Option[ActorSystem] = None

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = maxRetries, withinTimeRange = maxRetriesTimewindow) {
      case _: RestartRequestException => Restart
      case _: Exception => Restart
      case _ => Escalate
    }

  onActorTerminated {
    case _ => throw new Error("Unable to bootstrap the cluster system")
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(context.actorOf(Props(classOf[ServiceClusterBootstrapActor], nodeCfg), "bootstrap"))
    Launched('config -> nodeCfg)
  }

  onMessage {
    case m: ForwardToService => context.actorSelection("bootstrap").forward(m)
  }

}

