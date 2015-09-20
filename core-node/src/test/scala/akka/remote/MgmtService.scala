package akka.remote

import akka.actor.Address
import akka.remote.MgmtService.{Unblock, Block}
import akka.remote.transport.FailureInjectorTransportAdapter.{PassThru, Drop, One}
import rs.core.actors.{WithGlobalConfig, BaseActorSysevents, ActorWithComposableBehavior}

trait MgmtServiceEvents extends BaseActorSysevents {
  val Blocking = "Blocking".info
  val Unblocking = "Unblocking".info
  override def componentId: String = "Test.MgmtService"
}


object MgmtService {

  object Events extends MgmtServiceEvents

  case class Block(port: Int)
  case class Unblock(port: Int)

}

class MgmtService(id: String) extends ActorWithComposableBehavior with MgmtServiceEvents with WithGlobalConfig {

  private def exec(c: Any) = RARP(context.system).provider.transport.managementCommand(c)

  onMessage {
    case Block(p) =>
      Blocking('addr -> Address("akka.gremlin.tcp", "cluster", "localhost", p))
      exec(One(Address("akka.gremlin.tcp", "cluster", "localhost", p), Drop(1, 1)))
    case Unblock(p) =>
      Unblocking('addr -> Address("akka.gremlin.tcp", "cluster", "localhost", p))
      exec(One(Address("akka.gremlin.tcp", "cluster", "localhost", p), PassThru))
  }

}
