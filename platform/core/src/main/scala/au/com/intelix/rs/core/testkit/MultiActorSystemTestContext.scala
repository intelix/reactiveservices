package au.com.intelix.rs.core.testkit

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import au.com.intelix.config.WithBlankConfig
import au.com.intelix.essentials.uuid.UUIDTools
import au.com.intelix.evt.testkit.{EvtAssertions, TestEvtContext}
import au.com.intelix.evt.{CommonEvt, EvtContext, EvtSource, TraceE}
import com.typesafe.config._
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{BeforeAndAfterEach, Suite, Tag}
import au.com.intelix.rs.core.actors.StatelessActor

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object MultiActorSystemTestContext {

  object Evt {
    case object ActorSystemCreated extends TraceE
    case object ActorSystemTerminated extends TraceE
    case object TerminatingActorSystem extends TraceE
    case object DestroyingAllSystems extends TraceE
    case object DestroyingActor extends TraceE
    case object AllActorsTerminated extends TraceE
  }

}

trait ActorSystemWrapper {
  def underlyingSystem: ActorSystem

  def config: Config

  def stopActor(id: String)

  def start(props: Props, id: String): ActorRef

  def actorSelection(id: String) = underlyingSystem.actorSelection(id)

  def rootUserActorSelection(id: String) = actorSelection(s"/user/$id")
}

private case class Watch(ref: ActorRef)

private case class StopAll()


private object WatcherActor {

  object Evt {
    case object Watching extends TraceE
    case object WatchedActorGone extends TraceE
    case object AllWatchedActorsGone extends TraceE
    case object TerminatingActor extends TraceE
  }

  def props(componentId: String) = Props(new WatcherActor(componentId))
}

private class WatcherActor(id: String) extends StatelessActor {

  import WatcherActor._

  commonEvtFields('InstanceId -> id)

  var watched = Set[ActorRef]()

  onMessage {
    case StopAll() =>
      if (watched.isEmpty) {
        raise(Evt.AllWatchedActorsGone)
      }
      watched.foreach { a =>
        raise(Evt.TerminatingActor, 'Actor -> a)
        context.stop(a)
      }
    case Watch(ref) =>
      raise(Evt.Watching, 'Ref -> ref)
      watched = watched + ref
      context.watch(ref)
    case Terminated(ref) =>
      watched = watched match {
        case w if w contains ref =>
          raise(Evt.WatchedActorGone, 'Ref -> ref, 'Path -> ref.path.toSerializationFormat)
          if (w.size == 1) raise(Evt.AllWatchedActorsGone)
          w - ref
        case w => w
      }

  }
}

trait ConfigReference {
  def toConfig: String
}

case class ConfigFromFile(name: String) extends ConfigReference {
  override def toConfig: String = s"""include "$name"\n"""
}

case class ConfigFromContents(contents: String) extends ConfigReference {
  override def toConfig: String = contents + "\n"
}

trait MultiActorSystemTestContext extends BeforeAndAfterEach with TestEvtContext with WithBlankConfig with EvtContext with LazyLogging {

  self: Suite with ActorSystemManagement with EvtAssertions =>

  import MultiActorSystemTestContext._

  object OnlyThisTest extends Tag("OnlyThisTest")

  case class Wrapper(config: Config, underlyingSystem: ActorSystem, id: String, configName: String) extends ActorSystemWrapper {
    private val watcherComponentId = UUIDTools.generateUUID
    private val watcher = underlyingSystem.actorOf(WatcherActor.props(watcherComponentId))

    override def start(props: Props, id: String): ActorRef = {
      val newActor = underlyingSystem.actorOf(props, id)
      watcher ! Watch(newActor)
      newActor
    }

    override def stopActor(id: String) = {
      val futureActor = rootUserActorSelection(id).resolveOne(5.seconds)
      val actor = Await.result(futureActor, 15.seconds)
      raise(Evt.DestroyingActor, 'Actor -> actor)
      underlyingSystem.stop(actor)
      on anyNode expectSome of WatcherActor.Evt.WatchedActorGone +('Path -> actor.path.toSerializationFormat, 'InstanceId -> watcherComponentId)
      clearComponentEvents(watcherComponentId)
    }

    def stop() = {
      raise(Evt.TerminatingActorSystem, 'Name -> configName)
      val startCheckpoint = System.nanoTime()
      try {
        Await.result(underlyingSystem.terminate(), 120.seconds)
        raise(Evt.ActorSystemTerminated, 'Name -> configName, 'TerminatedInMs -> (System.nanoTime() - startCheckpoint) / 1000000)
      } catch {
        case _: Throwable => raise(CommonEvt.Evt.Error, 'Name -> configName, 'Message -> "Unable to terminate actor system. Attempting to continue...")
      }
    }

    def stopActors() = {
      val startCheckpoint = System.nanoTime()
      clearComponentEvents(watcherComponentId)
      watcher ! StopAll()
      on anyNode expectSome of WatcherActor.Evt.AllWatchedActorsGone + ('InstanceId -> watcherComponentId)
      clearComponentEvents(watcherComponentId)
      raise(Evt.AllActorsTerminated, 'TerminatedInMs -> (System.nanoTime() - startCheckpoint) / 1000000, 'System -> configName)
    }
  }


  private var systems = Map[String, Wrapper]()

  val akkaSystemId = "cluster"

  private def getSystem(instanceId: String, configs: ConfigReference*) = systems.get(instanceId) match {
    case None =>
      val config: Config = buildConfig(configs: _*)
      val sys = Wrapper(config, ActorSystem(akkaSystemId, config), akkaSystemId, instanceId)
      raise(Evt.ActorSystemCreated, 'instanceId -> instanceId, 'system -> akkaSystemId)
      systems = systems + (instanceId -> sys)
      sys
    case Some(x) => x
  }

  def buildConfig(configs: ConfigReference*): Config = {
    val config = configs.foldLeft[String]("") {
      case (cfg, next) =>
//        logger.debug(s"Adding config: $next")
        cfg + next.toConfig
    }
    ConfigFactory.parseString(config).resolve()
  }

  def locateExistingSystem(instanceId: String) = systems(instanceId)

  def withSystem[T](instanceId: String, configs: ConfigReference*)(f: ActorSystemWrapper => T): T = f(getSystem(instanceId, configs: _*))

  def destroySystem(name: String) = {
    systems.get(name).foreach(_.stop())
    systems = systems - name

  }

  def destroyAllSystems() = {
    raise(Evt.DestroyingAllSystems)
    systems.values.foreach(_.stop())
    systems = Map()
  }

  def destroyAllActors() = {
    logger.info(s"Destroying all actors")
    systems.values.foreach(_.stopActors())
    logger.info(s"Destroyed all actors")
  }


}