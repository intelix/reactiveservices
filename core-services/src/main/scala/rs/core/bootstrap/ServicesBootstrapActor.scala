package rs.core.bootstrap

import akka.actor.Props
import rs.core.ServiceKey
import rs.core.actors.{WithGlobalConfig, ActorWithComposableBehavior, BaseActorSysevents}
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
class ServicesBootstrapActor extends ActorWithComposableBehavior with ServicesBootstrapEvents with WithGlobalConfig {

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
    services foreach startProvider
  }

  onMessage {
    case ForwardToService(id, m) => context.actorSelection(id).forward(m)
  }
}
