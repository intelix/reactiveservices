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
import rs.core.actors.StatelessActor
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService
import rs.core.config.ConfigOps.wrap
import rs.core.config.NodeConfig
import rs.core.evt.{CommonEvt, EvtSource, InfoE, WarningE}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object ServiceClusterBootstrapActor {
  val EvtSourceId = "Cluster.Bootstrap"

  case object EvtStartingCluster extends InfoE

  case object EvtStoppingCluster extends InfoE

  case object EvtRestartingCluster extends WarningE

}

class ServiceClusterBootstrapActor(cfg: NodeConfig) extends StatelessActor {

  import ServiceClusterBootstrapActor._

  override implicit lazy val nodeCfg = cfg

  private val clusterSystemId = nodeCfg.asString("node.cluster.system-id", context.system.name)

  private var clusterSystem: Option[ActorSystem] = None

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 1 minutes, loggingEnabled = false) {
      case x: Exception =>
        raise(CommonEvt.EvtSupervisorRestartTrigger, 'Message -> x.getMessage, 'Cause -> x)
        Restart
      case _ => Escalate
    }

  onActorTerminated {
    case ref =>
      raise(EvtRestartingCluster)
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


  private def stopCluster(block: Boolean) = {
    raise(EvtStoppingCluster)
    clusterSystem.foreach { sys =>
      implicit val ec = context.dispatcher
      Await.result(sys.terminate(), 60 seconds)
    }
  }


  private def startCluster() = {
    raise(EvtStartingCluster)
    clusterSystem = Some(ActorSystem(clusterSystemId, nodeCfg.config))
    clusterSystem foreach { sys =>
      context.watch(sys.actorOf(Props[ClusterNodeActor], "node"))
    }
  }


  onMessage {
    case ForwardToService(id, m) => clusterSystem.foreach(_.actorSelection("/user/node/" + id) ! m)
  }
  override val evtSource: EvtSource = EvtSourceId
}

