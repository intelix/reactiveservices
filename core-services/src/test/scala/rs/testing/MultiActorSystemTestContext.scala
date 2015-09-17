package rs.testing

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import com.typesafe.config._
import org.scalatest.{BeforeAndAfterEach, Suite, Tag}
import org.slf4j.LoggerFactory
import rs.core.actors.ActorWithComposableBehavior
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.core.sysevents.support.EventAssertions
import rs.core.tools.UUIDTools

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

trait MultiActorSystemTestContextSysevents extends ComponentWithBaseSysevents {
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

private trait WatcherSysevents extends ComponentWithBaseSysevents {
  val Watching = 'Watching.trace
  val WatchedActorGone = 'WatchedActorGone.trace
  val AllWatchedActorsGone = 'AllWatchedActorsGone.trace
  val TerminatingActor = 'TerminatingActor.trace

  override def componentId: String = "Test.Watcher"
}

private object WatcherActor extends WatcherSysevents {
  def props(componentId: String) = Props(new WatcherActor(componentId))
}

private class WatcherActor(id: String) extends ActorWithComposableBehavior with WatcherSysevents with WithSyseventPublisher {
  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('InstanceId -> id)

  var watched = Set[ActorRef]()

  onMessage {
    case StopAll() =>
      if (watched.isEmpty) {
        logger.debug("AllWatchedActorsGone")
        AllWatchedActorsGone()
      }
      watched.foreach { a =>
        logger.debug(s"TerminatingActor $a")
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
          logger.debug(s"WatchedActorGone $ref")
          if (w.size == 1) AllWatchedActorsGone()
          w - ref
        case w => w
      }

  }
}

trait ConfigReference {
  def toConfig: Config
}

case class ConfigFromFile(name: String) extends ConfigReference {
  override def toConfig: Config = ConfigFactory.load(name)
}
case class ConfigFromContents(contents: String) extends ConfigReference {
  override def toConfig: Config = ConfigFactory.parseString(contents)
}

trait MultiActorSystemTestContext extends BeforeAndAfterEach with MultiActorSystemTestContextSysevents with WithSyseventPublisher {
  self: Suite with ActorSystemManagement with EventAssertions =>

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
      val actor = Await.result(futureActor, 5.seconds)
      DestroyingActor('Actor -> actor)
      underlyingSystem.stop(actor)
      expectSomeEventsWithTimeout(5000, WatcherActor.WatchedActorGone, 'Path -> actor.path.toSerializationFormat, 'InstanceId -> watcherComponentId)
      clearComponentEvents(watcherComponentId)
    }

    def stop() = {
      TerminatingActorSystem('Name -> configName)
      val startCheckpoint = System.nanoTime()
      try {
        stopActors()
      } catch {
        case x: Throwable => x.printStackTrace()
      }
      underlyingSystem.stop(watcher)
      underlyingSystem.shutdown()
      underlyingSystem.awaitTermination(60.seconds)
      ActorSystemTerminated('Name -> configName, 'TerminatedInMs -> (System.nanoTime() - startCheckpoint) / 1000000)
    }

    def stopActors() = {
      val startCheckpoint = System.nanoTime()
      clearComponentEvents(watcherComponentId)
      watcher ! StopAll()
      expectSomeEventsWithTimeout(30000, WatcherActor.AllWatchedActorsGone, 'InstanceId -> watcherComponentId)
      clearComponentEvents(watcherComponentId)
      AllActorsTerminated('TerminatedInMs -> (System.nanoTime() - startCheckpoint) / 1000000, 'System -> configName)
    }
  }



  private var systems = Map[String, Wrapper]()

  val akkaSystemId = "cluster"

  private def getSystem(instanceId: String, configs: ConfigReference*) = systems.get(instanceId) match {
    case None =>
      val config: Config = buildConfig(configs:_*)

      val sys = Wrapper(config, ActorSystem(akkaSystemId, config), akkaSystemId, instanceId)
      ActorSystemCreated('instanceId -> instanceId, 'system -> akkaSystemId)
      systems = systems + (instanceId -> sys)
      sys
    case Some(x) => x
  }

  def buildConfig(configs: ConfigReference*): Config = {
    val config = configs.foldLeft[Config](ConfigFactory.empty()) {
        case (cfg, next) =>
        logger.debug(s"Adding config: $next")
        cfg.withFallback(next.toConfig.resolve())
      }
    config
  }

  def locateExistingSystem(instanceId: String) = systems.get(instanceId).get

  def withSystem[T](instanceId: String, configs: ConfigReference*)(f: ActorSystemWrapper => T): T = f(getSystem(instanceId, configs:_*))

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




  // TODO consider extracting these

  override protected def beforeEach(): Unit = {
    LoggerFactory.getLogger("testseparator").debug("\n" * 3 + "-" * 120)
    super.beforeEach()
  }

  override protected def afterEach(): Unit = {
    LoggerFactory.getLogger("testseparator").debug(" " * 10 + "~" * 40 + " test finished " + "~" * 40)
    super.afterEach()
  }
}