package au.com.intelix.rs.core.testkit

import akka.actor.{ActorRef, ActorSystem, Props}
import au.com.intelix.essentials.uuid.UUIDTools
import au.com.intelix.evt.testkit.{EvtAssertions, EvtSelection}
import org.scalatest.Suite

trait ActorSystemTestContext extends EvtAssertions {
  self: Suite =>

  private var system: Option[ActorSystem] = None

  def buildSystem: ActorSystem

  def start(props: Props, id: String = UUIDTools.generateShortUUID) = system.map(_.actorOf(props, id))

  def stop(actor: Option[ActorRef], terminationEvent: EvtSelection) = system.flatMap { s =>
    actor.foreach { a =>
      s.stop(a)
      on anyNode expectSome of terminationEvent
    }
    None
  }

  def startSystem() = {
    system = Some(buildSystem)
    super.beforeAll()
  }

  def withSystem(f: ActorSystem => Unit) = system.foreach(f)

}