package akka.remote

import akka.actor.Address
import akka.remote.MgmtService.{Unblock, Block}
import akka.remote.transport.FailureInjectorTransportAdapter.{PassThru, Drop, One}
import rs.core.actors.{WithGlobalConfig, BaseActorSysevents, BasicActor}

trait MgmtServiceEvents extends BaseActorSysevents {
  val Blocking = "Blocking".info
  val Unblocking = "Unblocking".info
  override def componentId: String = "Test.MgmtService"
}


object MgmtService {

  object Events extends MgmtServiceEvents

  object Block {
    def apply(port: Int): Block = Block(Seq(port))
  }
  case class Block(port: Seq[Int])

  object Unblock {
    def apply(port: Int): Unblock = Unblock(Seq(port))
  }
  case class Unblock(port: Seq[Int])

}

class MgmtService(id: String) extends BasicActor with MgmtServiceEvents with WithGlobalConfig {

  private def exec(c: Any) = RARP(context.system).provider.transport.managementCommand(c)

  onMessage {
    case Block(ps) =>
      ps.foreach { p =>
        Blocking('addr -> Address("akka.gremlin.tcp", "cluster", "localhost", p))
        exec(One(Address("akka.gremlin.tcp", "cluster", "localhost", p), Drop(1, 1)))
      }
    case Unblock(ps) =>
      ps.foreach { p =>
        Unblocking('addr -> Address("akka.gremlin.tcp", "cluster", "localhost", p))
        exec(One(Address("akka.gremlin.tcp", "cluster", "localhost", p), PassThru))
      }
  }

}
