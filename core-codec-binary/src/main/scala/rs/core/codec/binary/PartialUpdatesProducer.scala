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

import akka.stream._
import akka.stream.scaladsl._
import rs.core.codec.binary.BinaryProtocolMessages._
import rs.core.stream.StreamState

import scala.collection.mutable

object PartialUpdatesProducer {

  def buildStage(): BidiFlow[BinaryDialectInbound, BinaryDialectInbound, BinaryDialectOutbound, BinaryDialectOutbound, Unit] = BidiFlow.wrap(FlowGraph.partial() { implicit b =>
    import FlowGraph.Implicits._

    class InboundRouter extends FlexiRoute[BinaryDialectInbound, FanOutShape2[BinaryDialectInbound, BinaryDialectInbound, BinaryDialectResetSubscription]](
      new FanOutShape2("resetRouter"), Attributes.name("resetRouter")) {

      import FlexiRoute._

      override def createRouteLogic(s: PortT): RouteLogic[BinaryDialectInbound] =
        new RouteLogic[BinaryDialectInbound] {

          override def initialState: State[Unit] = State(DemandFromAll(s.out0, s.out1)) { (ctx, _, el) =>
            el match {
              case m: BinaryDialectResetSubscription =>
                ctx.emit(s.out1)(m)
              case m =>
                ctx.emit(s.out0)(m)
            }
            SameState
          }
        }
    }

    class OutboundMerger extends FlexiMerge[BinaryDialectOutbound, FanInShape2[Any, Any, BinaryDialectOutbound]](
      new FanInShape2("fanIn"), Attributes.name("fanIn")) {

      import akka.stream.scaladsl.FlexiMerge._

      override def createMergeLogic(s: PortT): MergeLogic[BinaryDialectOutbound] = new MergeLogic[BinaryDialectOutbound] {

        private val lastUpdates: mutable.Map[Int, StreamState] = mutable.HashMap()

        def toPartial(x: BinaryDialectStreamStateUpdate): BinaryDialectOutbound = {

          val previous = synchronized {
            val value = lastUpdates get x.subjAlias
            lastUpdates.put(x.subjAlias, x.topicState)
            value
          }

          x.topicState.transitionFrom(previous) match {
            case Some(tran) => BinaryDialectStreamStateTransitionUpdate(x.subjAlias, tran)
            case _ => x
          }
        }

        override def initialState: State[_] = State(ReadPreferred(s.in0, s.in1)) { (ctx, input, element) =>
          element match {
            case BinaryDialectResetSubscription(subjAlias) =>
              lastUpdates.get(subjAlias) foreach { lastState =>
                ctx.emit(BinaryDialectStreamStateUpdate(subjAlias, lastState))
              }
            case m@BinaryDialectSubscriptionClosed(subjAlias) =>
              lastUpdates.remove(subjAlias)
              ctx.emit(m)
            case x: BinaryDialectStreamStateUpdate =>
              ctx.emit(toPartial(x))
            case x: BinaryDialectOutbound => ctx.emit(x)
            case _ =>
          }
          SameState
        }
      }
    }


    val inboundRouter = b.add(new InboundRouter)
    val outboundMerger = b.add(new OutboundMerger)

    inboundRouter.out1 ~> outboundMerger.in0

    BidiShape(FlowShape(inboundRouter.in, inboundRouter.out0), FlowShape(outboundMerger.in1, outboundMerger.out))
  })


}
