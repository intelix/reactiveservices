package rs.testkit

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import com.typesafe.config._
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{BeforeAndAfterEach, Suite, Tag}
import rs.core.actors.StatelessActor
import rs.core.config.WithBlankConfig
import rs.core.evt.{CommonEvt, EvtContext, EvtSource, TraceE}
import rs.core.utils.UUIDTools
import rs.testkit

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object MultiActorSystemTestContext {
  val EvtSourceId = "Test.ActorSystem"

  case object EvtActorSystemCreated extends TraceE

  case object EvtActorSystemTerminated extends TraceE

  case object EvtTerminatingActorSystem extends TraceE

  case object EvtDestroyingAllSystems extends TraceE

  case object EvtDestroyingActor extends TraceE

  case object EvtAllActorsTerminated extends TraceE

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
  val EvtSourceId = "Test.Watcher"

  case object EvtWatching extends TraceE

  case object EvtWatchedActorGone extends TraceE

  case object EvtAllWatchedActorsGone extends TraceE

  case object EvtTerminatingActor extends TraceE

  def props(componentId: String) = Props(new WatcherActor(componentId))
}

private class WatcherActor(id: String) extends StatelessActor {

  import WatcherActor._

  addEvtFields('InstanceId -> id)

  var watched = Set[ActorRef]()

  onMessage {
    case StopAll() =>
      if (watched.isEmpty) {
        raise(EvtAllWatchedActorsGone)
      }
      watched.foreach { a =>
        raise(EvtTerminatingActor, 'Actor -> a)
        context.stop(a)
      }
    case Watch(ref) =>
      raise(EvtWatching, 'Ref -> ref)
      watched = watched + ref
      context.watch(ref)
    case Terminated(ref) =>
      watched = watched match {
        case w if w contains ref =>
          raise(EvtWatchedActorGone, 'Ref -> ref, 'Path -> ref.path.toSerializationFormat)
          if (w.size == 1) raise(EvtAllWatchedActorsGone)
          w - ref
        case w => w
      }

  }
  override val evtSource: EvtSource = EvtSourceId
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

trait MultiActorSystemTestContext extends BeforeAndAfterEach with testkit.TestEvtContext with WithBlankConfig with EvtContext with LazyLogging {

  self: Suite with ActorSystemManagement with EvtAssertions =>

  import MultiActorSystemTestContext._

  override val evtSource: EvtSource = EvtSourceId

  object OnlyThisTest extends Tag("OnlyThisTest")

  case class Wrapper(config: Config, underlyingSystem: ActorSystem, id: String, configName: String) extends ActorSystemWrapper {
    private val watcherComponentId = UUIDTools.generateShortUUID
    private val watcher = underlyingSystem.actorOf(WatcherActor.props(watcherComponentId))

    override def start(props: Props, id: String): ActorRef = {
      val newActor = underlyingSystem.actorOf(props, id)
      watcher ! Watch(newActor)
      newActor
    }

    override def stopActor(id: String) = {
      val futureActor = rootUserActorSelection(id).resolveOne(5.seconds)
      val actor = Await.result(futureActor, 15.seconds)
      raise(EvtDestroyingActor, 'Actor -> actor)
      underlyingSystem.stop(actor)
      on anyNode expectSome of WatcherActor.EvtWatchedActorGone +('Path -> actor.path.toSerializationFormat, 'InstanceId -> watcherComponentId)
      clearComponentEvents(watcherComponentId)
    }

    def stop() = {
      raise(EvtTerminatingActorSystem, 'Name -> configName)
      val startCheckpoint = System.nanoTime()
      try {
        Await.result(underlyingSystem.terminate(), 120.seconds)
        raise(EvtActorSystemTerminated, 'Name -> configName, 'TerminatedInMs -> (System.nanoTime() - startCheckpoint) / 1000000)
      } catch {
        case _: Throwable => raise(CommonEvt.EvtError, 'Name -> configName, 'Message -> "Unable to terminate actor system. Attempting to continue...")
      }
    }

    def stopActors() = {
      val startCheckpoint = System.nanoTime()
      clearComponentEvents(watcherComponentId)
      watcher ! StopAll()
      on anyNode expectSome of WatcherActor.EvtAllWatchedActorsGone + ('InstanceId -> watcherComponentId)
      clearComponentEvents(watcherComponentId)
      raise(EvtAllActorsTerminated, 'TerminatedInMs -> (System.nanoTime() - startCheckpoint) / 1000000, 'System -> configName)
    }
  }


  private var systems = Map[String, Wrapper]()

  val akkaSystemId = "cluster"

  private def getSystem(instanceId: String, configs: ConfigReference*) = systems.get(instanceId) match {
    case None =>
      val config: Config = buildConfig(configs: _*)
      val sys = Wrapper(config, ActorSystem(akkaSystemId, config), akkaSystemId, instanceId)
      raise(EvtActorSystemCreated, 'instanceId -> instanceId, 'system -> akkaSystemId)
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

  def locateExistingSystem(instanceId: String) = systems.get(instanceId).get

  def withSystem[T](instanceId: String, configs: ConfigReference*)(f: ActorSystemWrapper => T): T = f(getSystem(instanceId, configs: _*))

  def destroySystem(name: String) = {
    systems.get(name).foreach(_.stop())
    systems = systems - name

  }

  def destroyAllSystems() = {
    raise(EvtDestroyingAllSystems)
    systems.values.foreach(_.stop())
    systems = Map()
  }

  def destroyAllActors() = {
    logger.info(s"Destroying all actors")
    systems.values.foreach(_.stopActors())
    logger.info(s"Destroyed all actors")
  }


}