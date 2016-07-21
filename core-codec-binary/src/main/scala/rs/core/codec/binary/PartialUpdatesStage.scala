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
package rs.core.codec.binary

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import rs.core.codec.binary.BinaryProtocolMessages._
import rs.core.config.ConfigOps.wrap
import rs.core.config.{NodeConfig, ServiceConfig}
import rs.core.stream.StreamState

import scala.collection.mutable

class PartialUpdatesStage extends BinaryDialectStageBuilder {

  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: NodeConfig): Option[BidiFlow[BinaryDialectInbound, BinaryDialectInbound, BinaryDialectOutbound, BinaryDialectOutbound, NotUsed]] =
    if (serviceCfg.asBoolean("partials.enabled", defaultValue = true)) Some(BidiFlow.fromGraph(new PartialUpdatesProducer(serviceCfg))) else None

  private class PartialUpdatesProducer(serviceCfg: ServiceConfig) extends GraphStage[BidiShape[BinaryDialectInbound, BinaryDialectInbound, BinaryDialectOutbound, BinaryDialectOutbound]] {
    val outBuffer = serviceCfg.asInt("partials.out-buffer-msg", defaultValue = 1)
    val inBuffer = serviceCfg.asInt("partials.in-buffer-msg", defaultValue = 1)
    val in1: Inlet[BinaryDialectInbound] = Inlet("ServiceBoundIn")
    val out1: Outlet[BinaryDialectInbound] = Outlet("ServiceBoundOut")
    val in2: Inlet[BinaryDialectOutbound] = Inlet("ClientBoundIn")
    val out2: Outlet[BinaryDialectOutbound] = Outlet("ClientBoundOut")
    override val shape: BidiShape[BinaryDialectInbound, BinaryDialectInbound, BinaryDialectOutbound, BinaryDialectOutbound] = BidiShape(in1, out1, in2, out2)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

      private val lastUpdates: mutable.Map[Int, StreamState] = mutable.HashMap()
      private val clientBound = mutable.Queue[BinaryDialectOutbound]()
      private val serviceBound = mutable.Queue[BinaryDialectInbound]()

      override def preStart(): Unit = {
        pull(in1)
        pull(in2)
      }

      setHandler(in1, new InHandler {
        override def onPush(): Unit = {
          grab(in1) match {
            case BinaryDialectResetSubscription(subjAlias) => lastUpdates.get(subjAlias) match {
              case Some(lastState) => pushToClient(BinaryDialectStreamStateUpdate(subjAlias, lastState))
              case None => pushToClient(BinaryDialectSubscriptionClosed(subjAlias))
            }
            case x: BinaryDialectInbound => pushToServer(x)
          }
          if (canPullFromClientNow) pull(in1)
        }
      })
      setHandler(out1, new OutHandler {
        override def onPull(): Unit = if (serviceBound.nonEmpty) {
          push(out1, serviceBound.dequeue())
          if (canPullFromClientNow) pull(in1)
        }
      })
      setHandler(in2, new InHandler {
        override def onPush(): Unit = {
          grab(in2) match {
            case x@BinaryDialectSubscriptionClosed(subjAlias) => lastUpdates.remove(subjAlias); pushToClient(x)
            case x: BinaryDialectStreamStateUpdate => pushToClient(toPartial(x))
            case x: BinaryDialectOutbound => pushToClient(x)
            case _ =>
          }
          if (canPullFromServiceNow) pull(in2)
        }
      })
      setHandler(out2, new OutHandler {
        override def onPull(): Unit = if (clientBound.nonEmpty) {
          push(out2, clientBound.dequeue())
          if (canPullFromServiceNow) pull(in2)
        }
      })

      def toPartial(x: BinaryDialectStreamStateUpdate): BinaryDialectOutbound = {
        val previous = lastUpdates get x.subjAlias
        lastUpdates.put(x.subjAlias, x.topicState)
        x.topicState.transitionFrom(previous) match {
          case Some(tran) => BinaryDialectStreamStateTransitionUpdate(x.subjAlias, tran)
          case _ => x
        }
      }

      def pushToClient(x: BinaryDialectOutbound) = {
        if (isAvailable(out2) && clientBound.isEmpty) push(out2, x)
        else {
          val maybeAlias = x match {
            case BinaryDialectStreamStateUpdate(alias, _) => Some(alias)
            case BinaryDialectStreamStateTransitionUpdate(alias, _) => Some(alias)
            case BinaryDialectInvalidRequest(alias) => Some(alias)
            case _ => None
          }
          maybeAlias foreach { alias =>
            clientBound.dequeueAll {
              case BinaryDialectStreamStateUpdate(a, _) => a == alias
              case BinaryDialectStreamStateTransitionUpdate(a, _) => a == alias
              case BinaryDialectInvalidRequest(a) => a == alias
              case _ => false
            }
          }
          clientBound.enqueue(x)
        }
        if (canPullFromServiceNow) pull(in2)
      }

      def pushToServer(x: BinaryDialectInbound) = {
        if (isAvailable(out1) && serviceBound.isEmpty) push(out1, x)
        else serviceBound.enqueue(x)
        if (canPullFromClientNow) pull(in1)
      }

      def canPullFromServiceNow = !hasBeenPulled(in2) && clientBound.size < outBuffer

      def canPullFromClientNow = !hasBeenPulled(in1) && serviceBound.size < inBuffer
    }
  }

}
