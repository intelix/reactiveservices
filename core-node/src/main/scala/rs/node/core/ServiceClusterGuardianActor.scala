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
package rs.node.core

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor._
import com.typesafe.config._
import com.typesafe.scalalogging.StrictLogging
import net.ceedubs.ficus.Ficus._
import rs.core.actors.{ActorWithComposableBehavior, BaseActorSysevents, WithGlobalConfig}
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService
import rs.core.config.GlobalConfig
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.node.core.ServiceClusterGuardianActor.RestartRequestException

import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.Scalaz._

trait ServiceClusterGuardianSysevents extends ComponentWithBaseSysevents with BaseActorSysevents {

  val Launched = "Launched".info

  override def componentId: String = "Cluster.Guardian"

}

object ServiceClusterGuardianActor {

  class RestartRequestException extends Exception

  def props(config: Config) = Props(new ServiceClusterGuardianActor(config))

  def start(config: Config)(implicit f: ActorRefFactory) = f.actorOf(props(config))
}

class ServiceClusterGuardianActor(config: Config)
  extends ActorWithComposableBehavior
  with ServiceClusterGuardianSysevents
  with StrictLogging
  with WithSyseventPublisher
  with WithGlobalConfig {

  private val maxRetries = config.as[Option[Int]]("node.cluster.max-retries") | -1
  private val maxRetriesTimewindow: Duration = config.as[Option[FiniteDuration]]("node.cluster.max-retries-window") match {
    case Some(d) => d
    case None => Duration.Inf
  }

  override implicit val globalCfg: GlobalConfig = GlobalConfig(config)

  private var clusterSystem: Option[ActorSystem] = None

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = maxRetries, withinTimeRange = maxRetriesTimewindow) {
      case _: RestartRequestException => Restart
      case _: Exception => Restart
      case _ => Escalate
    }

  onActorTerminated{
    case _ => throw new Error("Unable to bootstrap the cluster system")
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(context.actorOf(Props(classOf[ServiceClusterBootstrapActor], config), "bootstrap"))
    Launched('config -> config)
  }

  onMessage {
    case m: ForwardToService => context.actorSelection("bootstrap").forward(m)
  }

}

