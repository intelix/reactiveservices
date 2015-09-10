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

import akka.actor._
import com.typesafe.config._
import com.typesafe.scalalogging.StrictLogging
import net.ceedubs.ficus.Ficus._
import rs.core.actors.{ActorUtils, BaseActorSysevents}
import rs.core.config.ConfigOps.wrap
import rs.core.registry.ServiceRegistryActor
import rs.core.sysevents.SyseventOps.stringToSyseventOps
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.node.core.ServiceNodeActor.Start

import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.Scalaz._

trait ServiceClusterBootstrapSysevents extends ComponentWithBaseSysevents with BaseActorSysevents {

  val StartingCluster = "StartingCluster".info
  val StoppingCluster = "StoppingCluster".info

  override def componentId: String = "Cluster.Bootstrap"
}

object ServiceClusterBootstrapActor {
}

class ServiceClusterBootstrapActor(implicit val cfg: Config)
  extends ActorUtils
  with StrictLogging
  with ServiceClusterBootstrapSysevents
  with WithSyseventPublisher {

//  private val blockingWaitTimeout = cfg[FiniteDuration]("node.cluster.termination-wait-timeout", 10 seconds)
  private val clusterSystemId =  cfg.asString("node.cluster.system-id", context.system.name)

  private var clusterSystem: Option[ActorSystem] = None


  override def receive: Actor.Receive = {
    case Terminated(ref) => throw new Exception("Restarting cluster subsystem")
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
      //Await.result(sys.terminate(), blockingWaitTimeout)
      sys.shutdown()
      sys.awaitTermination()
    }


  private def startCluster() = {
    StartingCluster { ctx =>
      clusterSystem = Some(ActorSystem(clusterSystemId, cfg))
      clusterSystem foreach { sys =>
        ServiceRegistryActor.start(sys)
        context.watch(sys.actorOf(Props[ServiceNodeActor], "node")) ! Start
      }
    }
  }

}

