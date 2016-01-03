package rs.testkit

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import com.typesafe.config._
import org.scalatest.{BeforeAndAfterEach, Suite, Tag}
import rs.core.actors.StatelessActor
import rs.core.config.WithBlankConfig
import rs.core.sysevents.{CommonEvt, EvtPublisherContext}
import rs.core.tools.UUIDTools
import rs.testkit

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

trait MultiActorSystemTestContextSysevents extends CommonEvt {
  override def componentId: String = "Test.ActorSystem"

  val ActorSystemCreated = 'ActorSystemCreated.trace
  val ActorSystemTerminated = 'ActorSystemTerminated.trace
  val TerminatingActorSystem = 'TerminatingActorSystem.trace
  val DestroyingAllSystems = 'DestroyingAllSystems.trace
  val DestroyingActor = 'DestroyingActor.trace
  val AllActorsTerminated = 'AllActorsTerminated.trace
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

private trait WatcherSysevents extends CommonEvt {
  val Watching = 'Watching.trace
  val WatchedActorGone = 'WatchedActorGone.trace
  val AllWatchedActorsGone = 'AllWatchedActorsGone.trace
  val TerminatingActor = 'TerminatingActor.trace

  override def componentId: String = "Test.Watcher"
}

private object WatcherActor extends WatcherSysevents {
  def props(componentId: String) = Props(new WatcherActor(componentId))
}

private class WatcherActor(id: String) extends StatelessActor with WatcherSysevents {

  addEvtFields('InstanceId -> id)

  var watched = Set[ActorRef]()

  onMessage {
    case StopAll() =>
      if (watched.isEmpty) {
        AllWatchedActorsGone()
      }
      watched.foreach { a =>
        TerminatingActor('Actor -> a)
        context.stop(a)
      }
    case Watch(ref) =>
      Watching('Ref -> ref)
      watched = watched + ref
      context.watch(ref)
    case Terminated(ref) =>
      watched = watched match {
        case w if w contains ref =>
          WatchedActorGone('Ref -> ref, 'Path -> ref.path.toSerializationFormat)
          if (w.size == 1) AllWatchedActorsGone()
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

trait MultiActorSystemTestContext
  extends BeforeAndAfterEach
    with MultiActorSystemTestContextSysevents
    with testkit.WithEvtCollector
    with WithBlankConfig
    with EvtPublisherContext
    with WithTestSeparator {

  self: Suite with ActorSystemManagement with EvtAssertions =>

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
      DestroyingActor('Actor -> actor)
      underlyingSystem.stop(actor)
      on anyNode expectSome of WatcherActor.WatchedActorGone +('Path -> actor.path.toSerializationFormat, 'InstanceId -> watcherComponentId)
      clearComponentEvents(watcherComponentId)
    }

    def stop() = {
      TerminatingActorSystem('Name -> configName)
      val startCheckpoint = System.nanoTime()
      try {
        Await.result(underlyingSystem.terminate(), 60.seconds)
        ActorSystemTerminated('Name -> configName, 'TerminatedInMs -> (System.nanoTime() - startCheckpoint) / 1000000)
      } catch {
        case _: Throwable => Error('Name -> configName, 'Message -> "Unable to terminate actor system. Attempting to continue...")
      }
    }

    def stopActors() = {
      val startCheckpoint = System.nanoTime()
      clearComponentEvents(watcherComponentId)
      watcher ! StopAll()
      on anyNode expectSome of WatcherActor.AllWatchedActorsGone + ('InstanceId -> watcherComponentId)
      clearComponentEvents(watcherComponentId)
      AllActorsTerminated('TerminatedInMs -> (System.nanoTime() - startCheckpoint) / 1000000, 'System -> configName)
    }
  }


  private var systems = Map[String, Wrapper]()

  val akkaSystemId = "cluster"

  private def getSystem(instanceId: String, configs: ConfigReference*) = systems.get(instanceId) match {
    case None =>
      val config: Config = buildConfig(configs: _*)
      val sys = Wrapper(config, ActorSystem(akkaSystemId, config), akkaSystemId, instanceId)
      ActorSystemCreated('instanceId -> instanceId, 'system -> akkaSystemId)
      systems = systems + (instanceId -> sys)
      sys
    case Some(x) => x
  }

  def buildConfig(configs: ConfigReference*): Config = {
    val config = configs.foldLeft[String]("") {
      case (cfg, next) =>
        logger.debug(s"Adding config: $next")
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
    DestroyingAllSystems()
    systems.values.foreach(_.stop())
    systems = Map()
  }

  def destroyAllActors() = {
    logger.info(s"Destroying all actors")
    systems.values.foreach(_.stopActors())
    logger.info(s"Destroyed all actors")
  }


}