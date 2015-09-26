package rs.testing

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor.{Props, OneForOneStrategy, SupervisorStrategy, ActorSystem}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import rs.core.actors.{WithGlobalConfig, BasicActor, BaseActorSysevents}
import rs.core.bootstrap.ServicesBootstrapActor
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService
import rs.core.config.ConfigOps.wrap
import rs.core.config.GlobalConfig
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents

import scala.concurrent.duration._
import scala.language.postfixOps


trait BasicClusterBootstrapSysevents extends ComponentWithBaseSysevents with BaseActorSysevents {

  val StartingCluster = "StartingCluster".info
  val StoppingCluster = "StoppingCluster".info

  override def componentId: String = "Cluster.Bootstrap"
}

object BasicClusterBootstrapSysevents extends BasicClusterBootstrapSysevents

object BasicClusterBootstrap {
}

class BasicClusterBootstrap(implicit val cfg: Config)
  extends BasicActor
  with StrictLogging
  with BasicClusterBootstrapSysevents
  with WithSyseventPublisher
  with WithGlobalConfig {

  //  private val blockingWaitTimeout = cfg[FiniteDuration]("node.cluster.termination-wait-timeout", 10 seconds)
  private val clusterSystemId = cfg.asString("node.cluster.system-id", context.system.name)

  private var clusterSystem: Option[ActorSystem] = None


  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 1 minutes) {
      case _: Exception => Restart
      case _ => Escalate
    }

  onActorTerminated {
    case ref =>
      throw new Exception("Restarting cluster subsystem")
  }

  override implicit val globalCfg: GlobalConfig = GlobalConfig(cfg)

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
      sys.shutdown()
      sys.awaitTermination()
    }


  private def startCluster() = {
    StartingCluster { ctx =>
      clusterSystem = Some(ActorSystem(clusterSystemId, cfg))
      clusterSystem foreach { sys =>
        context.watch(sys.actorOf(Props[ServicesBootstrapActor], "node"))
      }
    }
  }

  onMessage {
    case ForwardToService(id, m) => clusterSystem.foreach(_.actorSelection("/user/node/" + id) ! m)
  }

}