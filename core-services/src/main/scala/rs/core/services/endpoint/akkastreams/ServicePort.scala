package rs.core.services.endpoint.akkastreams

import akka.actor.ActorRefFactory
import akka.dispatch.Futures
import akka.pattern.Patterns
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, UniformFanOutShape}
import rs.core.services.Messages._
import rs.core.services.internal.{SignalPort, StreamAggregatorActor}
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents


trait ServicePortSysevents extends ComponentWithBaseSysevents {

  val FlowStarting = "FlowStarting".info
  val FlowStopping = "FlowStopping".info
  val SignalRequest = "SignalRequest".info
  val SignalResponse = "SignalResponse".info
  val SignalExpired = "SignalExpired".info
  val SignalFailure = "SignalFailure".warn

  override def componentId: String = "ServicePort"
}


object ServicePort extends ServicePortSysevents {


  def buildFlow(tokenId: String)(implicit context: ActorRefFactory, syseventPub: WithSyseventPublisher) = Flow.wrap[ServiceInboundMessage, ServiceOutboundMessage, Any](FlowGraph.partial() { implicit b =>

    import FlowGraph.Implicits._

    implicit val ec = context.dispatcher

    class MessageRouter[T] extends FlexiRoute[T, UniformFanOutShape[T, T]](new UniformFanOutShape(2), Attributes.name("Router")) {

      import FlexiRoute._

      override def createRouteLogic(s: PortT): RouteLogic[T] = new RouteLogic[T] {
        override def initialState: State[Unit] =
          State(DemandFromAll(s.out(0), s.out(1))) {
            (ctx, _, el) =>
              el match {
                case x: OpenSubscription => ctx.emit(s.out(0))(el)
                case x: CloseSubscription => ctx.emit(s.out(0))(el)
                case x: Signal => ctx.emit(s.out(1))(el)
                case x =>
              }
              SameState
          }

        override def initialCompletionHandling: CompletionHandling = CompletionHandling(
          onUpstreamFailure = (ctx, cause)  => ctx.finish(),
          onUpstreamFinish = ctx => ctx.finish(),
          onDownstreamFinish = (ctx, _)  => { ctx.finish(); SameState }
        )
      }

    }

    val aggregator = context.actorOf(StreamAggregatorActor.props(tokenId), s"aggregator-$tokenId")
    val signalPort = context.actorOf(SignalPort.props, s"signal-port-$tokenId")

    val lifecycleMonitor = new PushStage[ServiceInboundMessage, ServiceInboundMessage] {
      override def onPush(elem: ServiceInboundMessage, ctx: Context[ServiceInboundMessage]): SyncDirective = {
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

    val signalExecStage = b.add(Flow[ServiceInboundMessage].mapAsyncUnordered[ServiceOutboundMessage](100) {
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


    val merge = b.add(MergePreferred[ServiceOutboundMessage](1))

    val publisherSource = b.add(publisher)

    val router = b.add(new MessageRouter[ServiceInboundMessage])

    val monitor = b.add(Flow[ServiceInboundMessage].transform(() => lifecycleMonitor))

    monitor ~>  router.in
                router.out(0)   ~> requestSink // stream subscriptions
                router.out(1)   ~> signalExecStage ~> merge.preferred // signals
                                   publisherSource ~> merge

    FlowShape(monitor.inlet, merge.out)

  })

}
