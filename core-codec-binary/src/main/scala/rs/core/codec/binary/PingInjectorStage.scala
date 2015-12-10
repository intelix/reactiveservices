/*
 * Copyright 2014-15 Intelix Pty Ltd
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
package rs.core.codec.binary

import akka.stream.scaladsl._
import akka.stream.{BidiShape, FlowShape}
import rs.core.codec.binary.BinaryProtocolMessages._
import rs.core.config.ConfigOps.wrap
import rs.core.config.{NodeConfig, ServiceConfig}
import rs.core.sysevents.{CommonEvt, EvtPublisher}

import scala.concurrent.duration._
import scala.language.postfixOps

trait PingInjectorEvt extends CommonEvt {
  val ServerClientPing = "ServerClientPing".trace

  override def componentId: String = "Endpoint.PingInjector"
}

object PingInjectorEvt extends PingInjectorEvt

class PingInjectorStage extends BinaryDialectStageBuilder {

  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: NodeConfig) =
    if (serviceCfg.asBoolean("ping.enabled", defaultValue = true))
      Some(BidiFlow.wrap(FlowGraph.partial() { implicit b =>
        import FlowGraph.Implicits._
        import PingInjectorEvt._
        implicit val publisher = EvtPublisher(nodeCfg, 'token -> sessionId)

        val interval = serviceCfg.asFiniteDuration("ping.interval", 30 seconds)

        val top = b.add(Flow[BinaryDialectInbound].filter {
          case BinaryDialectPong(ts) =>
            val now = System.currentTimeMillis() % Int.MaxValue
            val diff = now - ts
            ServerClientPing('ms -> diff)
            false
          case t => true
        })

        val merge = b.add(MergePreferred[BinaryDialectOutbound](1))
        val pingSource = Source(interval, interval, None).map(_ => BinaryDialectPing((System.currentTimeMillis() % Int.MaxValue).toInt))

        pingSource ~> merge.preferred

        BidiShape(top, FlowShape(merge.in(0), merge.out))
      }))
    else None

}
