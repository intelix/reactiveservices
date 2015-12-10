package rs.testing

import akka.actor.{ActorRef, ActorSystem, Props}
import org.scalatest.Suite
import rs.core.sysevents.Sysevent
import rs.core.sysevents.support.EvtAssertions
import rs.core.tools.UUIDTools

trait ActorSystemTestContext extends EvtAssertions {
  self: Suite =>

  private var system: Option[ActorSystem] = None

  def buildSystem: ActorSystem

  def start(props: Props, id: String = UUIDTools.generateShortUUID) = system.map(_.actorOf(props, id))

  def stop(actor: Option[ActorRef], terminationEvent: Sysevent) = system.flatMap { s =>
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