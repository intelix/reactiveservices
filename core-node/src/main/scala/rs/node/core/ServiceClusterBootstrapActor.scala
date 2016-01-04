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
import rs.core.actors.{CommonActorEvt, StatelessActor}
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService
import rs.core.config.ConfigOps.wrap
import rs.core.config.NodeConfig
import rs.core.sysevents.CommonEvt

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

trait ServiceClusterBootstrapActorEvt extends CommonEvt with CommonActorEvt {

  val StartingCluster = "StartingCluster".info
  val StoppingCluster = "StoppingCluster".info
  val RestartingCluster = "RestartingCluster".warn

  override def componentId: String = "Cluster.Bootstrap"
}

object ServiceClusterBootstrapActorEvt extends ServiceClusterBootstrapActorEvt

class ServiceClusterBootstrapActor(cfg: NodeConfig) extends StatelessActor with ServiceClusterBootstrapActorEvt {

  override implicit lazy val nodeCfg = cfg

  private val clusterSystemId = nodeCfg.asString("node.cluster.system-id", context.system.name)

  private var clusterSystem: Option[ActorSystem] = None

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 1 minutes, loggingEnabled = false) {
      case x: Exception =>
        ServiceClusterBootstrapActorEvt.SupervisorRestartTrigger('Message -> x.getMessage, 'Cause -> x)
        Restart
      case _ => Escalate
    }

  onActorTerminated {
    case ref =>
      RestartingCluster()
      throw new Exception("Restarting cluster subsystem")
  }


  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    stopCluster(true)
    super.postStop()
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    startCluster()
  }


  private def stopCluster(block: Boolean) =
    clusterSystem.foreach { sys =>
      implicit val ec = context.dispatcher
      Await.result(sys.terminate(), 60 seconds)
    }


  private def startCluster() = {
    StartingCluster { ctx =>
      clusterSystem = Some(ActorSystem(clusterSystemId, nodeCfg.config))
      clusterSystem foreach { sys =>
        context.watch(sys.actorOf(Props[ClusterNodeActor], "node"))
      }
    }
  }

  onMessage {
    case ForwardToService(id, m) => clusterSystem.foreach(_.actorSelection("/user/node/" + id) ! m)
  }

}

