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
package rs.core.services.endpoint.akkastreams

import akka.actor.ActorRefFactory
import akka.dispatch.Futures
import akka.pattern.Patterns
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, UniformFanOutShape}
import rs.core.config.ConfigOps.wrap
import rs.core.config.NodeConfig
import rs.core.services.Messages._
import rs.core.services.internal.{SignalPort, StreamAggregatorActor}
import rs.core.sysevents.{CommonEvt, EvtPublisher}


trait ServicePortEvt extends CommonEvt {

  val FlowStarting = "FlowStarting".info
  val FlowStopping = "FlowStopping".info
  val SignalRequest = "SignalRequest".info
  val SignalResponse = "SignalResponse".info
  val SignalExpired = "SignalExpired".info
  val SignalFailure = "SignalFailure".warn

  override def componentId: String = "ServicePort"
}


object ServicePort extends ServicePortEvt {


  def buildFlow(tokenId: String)(implicit context: ActorRefFactory, nodeCfg: NodeConfig) = Flow.fromGraph(GraphDSL.create() { implicit b =>

    import GraphDSL.Implicits._

    implicit val ec = context.dispatcher

    implicit val evtPublisher = EvtPublisher(nodeCfg)

    val signalParallelism = nodeCfg.asInt("signal-parallelism", 100)

    class MessageRouter extends GraphStage[UniformFanOutShape[ServiceInbound, ServiceInbound]] {
      override val shape: UniformFanOutShape[ServiceInbound, ServiceInbound] = new UniformFanOutShape(2)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
        val pendingSignals = scala.collection.mutable.Queue[ServiceInbound]()
        val pendingSubscriptions = scala.collection.mutable.Queue[ServiceInbound]()

        override def preStart(): Unit = pull(shape.in)

        setHandler(shape.in, new InHandler {
          override def onPush(): Unit = {
            grab(shape.in) match {
              case x: Signal =>
                pendingSignals.enqueue(x)
                doPushSignal()
              case x: OpenSubscription =>
                pendingSubscriptions.enqueue(x)
                doPushSubscription()
              case x: CloseSubscription =>
                pendingSubscriptions.enqueue(x)
                doPushSubscription()
              case _ =>
            }
            doPull()
          }
        })
        setHandler(shape.out(0), new OutHandler {
          override def onPull(): Unit = doPushSubscription()
        })
        setHandler(shape.out(1), new OutHandler {
          override def onPull(): Unit = doPushSignal()
        })

        def doPull() = if (canPull) pull(shape.in)

        def canPull = !hasBeenPulled(shape.in) && hasCapacity

        def hasCapacity = pendingSignals.size < 64 && pendingSubscriptions.size < 64

        def doPushSignal() = if (canPushSignal) {
          push(shape.out(1), pendingSignals.dequeue())
          doPull()
        }

        def canPushSignal = isAvailable(shape.out(1)) && pendingSignals.nonEmpty

        def doPushSubscription() = if (canPushSubscription) {
          push(shape.out(0), pendingSubscriptions.dequeue())
          doPull()
        }

        def canPushSubscription = isAvailable(shape.out(0)) && pendingSubscriptions.nonEmpty
      }

    }

    val aggregator = context.actorOf(StreamAggregatorActor.props(tokenId), s"aggregator-$tokenId")
    val signalPort = context.actorOf(SignalPort.props, s"signal-port-$tokenId")

    val lifecycleMonitor = new PushStage[ServiceInbound, ServiceInbound] {
      override def onPush(elem: ServiceInbound, ctx: Context[ServiceInbound]): SyncDirective = {
        ctx.push(elem)
      }

      @throws[Exception](classOf[Exception])
      override def preStart(ctx: LifecycleContext): Unit = {
        FlowStarting('token -> tokenId)
        super.preStart(ctx)
      }

      @throws[Exception](classOf[Exception])
      override def postStop(): Unit = {
        FlowStopping { ctx =>
          ctx + ('token -> tokenId)
          context.stop(aggregator)
          context.stop(signalPort)
        }
        super.postStop()
      }
    }

    val publisher = Source.actorPublisher(ServicePortStreamSource.props(aggregator, tokenId))

    val requestSink = b.add(Sink.actorSubscriber(ServicePortSubscriptionRequestSinkSubscriber.props(aggregator, tokenId)))

    val signalExecStage = b.add(Flow[ServiceInbound].mapAsyncUnordered[ServiceOutbound](signalParallelism) {
      case m: Signal =>
        val start = System.nanoTime()
        val expiredIn = m.expireAt - System.currentTimeMillis()
        SignalRequest('correlation -> m.correlationId, 'expiry -> m.expireAt, 'expiredin -> expiredIn, 'subj -> m.subj, 'group -> m.orderingGroup)
        expiredIn match {
          case expireInMillis if expireInMillis > 0 =>
            Patterns.ask(signalPort, m, expireInMillis).recover {
              case t =>
                SignalExpired('correlation -> m.correlationId, 'subj -> m.subj)
                SignalAckFailed(m.correlationId, m.subj, None)
            }.map {
              case t: SignalAckOk =>
                val diff = System.nanoTime() - start
                SignalResponse('success -> true, 'correlation -> m.correlationId, 'subj -> m.subj, 'response -> String.valueOf(t), 'ms -> ((diff / 1000).toDouble / 1000))
                t
              case t: SignalAckFailed =>
                val diff = System.nanoTime() - start
                SignalResponse('success -> false, 'correlation -> m.correlationId, 'subj -> m.subj, 'response -> String.valueOf(t), 'ms -> ((diff / 1000).toDouble / 1000))
                t
              case t =>
                SignalFailure('correlation -> m.correlationId, 'subj -> m.subj, 'response -> String.valueOf(t))
                SignalAckFailed(m.correlationId, m.subj, None)
            }
          case _ => Futures.successful(SignalAckFailed(m.correlationId, m.subj, None))
        }
      case m: CloseSubscription =>
        Invalid('type -> m.getClass, 'reason -> "Signal expected")
        Futures.successful(SignalAckFailed(None, m.subj, None))
      case m: OpenSubscription =>
        Invalid('type -> m.getClass, 'reason -> "Signal expected")
        Futures.successful(SignalAckFailed(None, m.subj, None))
    })


    val merge = b.add(MergePreferred[ServiceOutbound](1))

    val publisherSource = b.add(publisher)

    val router = b.add(new MessageRouter)

    val monitor = b.add(Flow[ServiceInbound].transform(() => lifecycleMonitor))

    monitor ~>  router.in
                router.out(0) ~>  requestSink // stream subscriptions
                router.out(1) ~>  signalExecStage ~>  merge.preferred // signals
                                                      merge <~ publisherSource

    FlowShape(monitor.in, merge.out)

  })

}
