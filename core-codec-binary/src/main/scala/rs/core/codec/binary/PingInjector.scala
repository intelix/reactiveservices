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

import scala.concurrent.duration._
import scala.language.postfixOps

object PingInjector {

  def buildStage(interval: FiniteDuration = 30 seconds): BidiFlow[BinaryDialectInboundMessage, BinaryDialectInboundMessage, BinaryDialectOutboundMessage, BinaryDialectOutboundMessage, Unit] =
    BidiFlow.wrap(FlowGraph.partial() { implicit b =>
      import FlowGraph.Implicits._

      val top = b.add(Flow[BinaryDialectInboundMessage].filter {
        case BinaryDialectPong(ts) =>
          val now = System.currentTimeMillis() % Int.MaxValue
          val latency = (now - ts) / 2
          // TODO handle latency
          false
        case t => true
      })

      val merge = b.add(MergePreferred[BinaryDialectOutboundMessage](1))
      val pingSource = Source(interval, interval, None).map(_ => BinaryDialectPing((System.currentTimeMillis() % Int.MaxValue).toInt))

      pingSource ~> merge.preferred

      BidiShape(top, FlowShape(merge.in(0), merge.out))
    })

}
