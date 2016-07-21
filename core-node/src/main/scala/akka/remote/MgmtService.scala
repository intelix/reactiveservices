/*
 * Copyright 2014-16 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package akka.remote

import akka.actor.Address
import akka.remote.MgmtService._
import akka.remote.transport.FailureInjectorTransportAdapter.{Drop, One, PassThru}
import rs.core.actors.StatelessActor
import rs.core.evt.{EvtSource, InfoE}

object MgmtService {

  val EvtSourceId = "Test.MgmtService"

  case object EvtBlocking extends InfoE

  case object EvtUnblocking extends InfoE


  object Block {
    def apply(port: Int): Block = Block(Seq(port))
  }

  case class Block(port: Seq[Int])

  object Unblock {
    def apply(port: Int): Unblock = Unblock(Seq(port))
  }

  case class Unblock(port: Seq[Int])

}

class MgmtService extends StatelessActor {

  private def exec(c: Any) = RARP(context.system).provider.transport.managementCommand(c)

  onMessage {
    case Block(ps) =>
      ps.foreach { p =>
        raise(EvtBlocking, 'addr -> Address("akka.gremlin.tcp", "cluster", "localhost", p))
        exec(One(Address("akka.gremlin.tcp", "cluster", "localhost", p), Drop(1, 1)))
      }
    case Unblock(ps) =>
      ps.foreach { p =>
        raise(EvtUnblocking, 'addr -> Address("akka.gremlin.tcp", "cluster", "localhost", p))
        exec(One(Address("akka.gremlin.tcp", "cluster", "localhost", p), PassThru))
      }
  }
  override val evtSource: EvtSource = EvtSourceId
}
