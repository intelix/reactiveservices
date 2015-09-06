package rs.node.core

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor._
import com.typesafe.config._
import com.typesafe.scalalogging.StrictLogging
import net.ceedubs.ficus.Ficus._
import rs.core.actors.BaseActorSysevents
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.node.core.ServiceClusterGuardianActor.RestartRequestException

import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.Scalaz._

trait ServiceClusterGuardianSysevents extends ComponentWithBaseSysevents with BaseActorSysevents {

  override def componentId: String = "Cluster.Guardian"
}

object ServiceClusterGuardianActor {

  class RestartRequestException extends Exception

  def props(config: Config) = Props(new ServiceClusterGuardianActor(config))

  def start(config: Config)(implicit f: ActorRefFactory) = f.actorOf(props(config))
}

class ServiceClusterGuardianActor(config: Config)
  extends Actor
  with ServiceClusterBootstrapSysevents
  with StrictLogging
  with WithSyseventPublisher {

  private val maxRetries = config.as[Option[Int]]("node.cluster.max-retries") | -1
  private val maxRetriesTimewindow: Duration = config.as[Option[FiniteDuration]]("node.cluster.max-retries-window") match {
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

  override def receive: Actor.Receive = {
    case Terminated(_) => throw new Error("Unable to bootstrap the cluster system")
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(context.actorOf(Props(classOf[ServiceClusterBootstrapActor], config), "bootstrap"))
  }

}

