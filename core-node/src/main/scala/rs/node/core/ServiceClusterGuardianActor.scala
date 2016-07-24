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
import au.com.intelix.rs.core.actors.StatelessActor
import au.com.intelix.rs.core.bootstrap.ServicesBootstrapActor.ForwardToService
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.config.RootConfig
import au.com.intelix.evt.{CommonEvt, EvtSource, InfoE}
import rs.node.core.ServiceClusterGuardianActor.{EvtLaunched, RestartRequestException}

import scala.concurrent.duration._
import scala.language.postfixOps

object ServiceClusterGuardianActor {
  val EvtSourceId = "Cluster.Guardian"

  case object EvtLaunched extends InfoE

  class RestartRequestException extends Exception

  def props(config: RootConfig) = Props(new ServiceClusterGuardianActor(config))

  def start(config: RootConfig)(implicit f: ActorRefFactory) = f.actorOf(props(config))
}

class ServiceClusterGuardianActor(cfg: RootConfig) extends StatelessActor {
  import ServiceClusterGuardianActor._

  override implicit lazy val nodeCfg: RootConfig = cfg

  private val maxRetries = nodeCfg.asInt("node.cluster.max-retries", -1)
  private val maxRetriesTimewindow = nodeCfg.asOptFiniteDuration("node.cluster.max-retries-window") match {
    case Some(d) => d
    case None => Duration.Inf
  }

  private var clusterSystem: Option[ActorSystem] = None

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = maxRetries, withinTimeRange = maxRetriesTimewindow, loggingEnabled = false) {
      case x: RestartRequestException =>
        raise(CommonEvt.EvtSupervisorRestartTrigger, 'Message -> x.getMessage, 'Cause -> x)
        Restart
      case x: Exception =>
        raise(CommonEvt.EvtSupervisorRestartTrigger, 'Message -> x.getMessage, 'Cause -> x)
        Restart
      case _ => Escalate
    }

  onActorTerminated {
    case _ => throw new Error("Unable to bootstrap the node")
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(context.actorOf(Props(classOf[ServiceClusterBootstrapActor], nodeCfg), "bootstrap"))
    raise(EvtLaunched, 'config -> nodeCfg)
  }

  onMessage {
    case m: ForwardToService => context.actorSelection("bootstrap").forward(m)
  }
  override val evtSource: EvtSource = EvtSourceId
}

