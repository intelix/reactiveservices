package rs.core.services.endpoint.akkastreams

import akka.actor.ActorRefFactory
import akka.dispatch.Futures
import akka.pattern.Patterns
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, UniformFanOutShape}
import rs.core.services.Messages._
import rs.core.services.internal.{SignalPort, StreamAggregatorActor}

object EndpointFlow {


  def buildFlow(id: String)(implicit context: ActorRefFactory) = Flow.wrap[ServiceInboundMessage, ServiceOutboundMessage, Any](FlowGraph.partial() { implicit b =>

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


        override def postStop(): Unit = {
          println(s"!>>> Router stopped")
          super.postStop()
        }

        override def initialCompletionHandling: CompletionHandling = CompletionHandling(
          onUpstreamFailure = (ctx, cause)  => ctx.finish(),
          onUpstreamFinish = ctx => ctx.finish(),
          onDownstreamFinish = (ctx, _)  => { ctx.finish(); SameState }
        )
      }


    }




    println(s"!>>> Starting aggregator, $id")
    val aggregator = context.actorOf(StreamAggregatorActor.props(), s"aggregator-$id")
    val signalPort = context.actorOf(SignalPort.props, s"signalport-$id")

    println(s"!>>> aggregator: $aggregator")


    val lifecycleMonitor = new PushStage[ServiceInboundMessage, ServiceInboundMessage] {
      override def onPush(elem: ServiceInboundMessage, ctx: Context[ServiceInboundMessage]): SyncDirective = {
        ctx.push(elem)
      }


      @throws[Exception](classOf[Exception])
      override def preStart(ctx: LifecycleContext): Unit = {
        super.preStart(ctx)
      }


      @throws[Exception](classOf[Exception])
      override def postStop(): Unit = {
        println(s"!>>> lifecycleMonitor stopping .... ")
        // terminating
        context.stop(aggregator)
        context.stop(signalPort)
        super.postStop()
      }

    }





    val publisher = Source.actorPublisher(ServiceSubscriptionStreamSource.props(aggregator))

    val requestSink = b.add(Sink.actorSubscriber(ServicePortSubscriptionRequestSinkSubscriber.props(aggregator)))

    val signalExecStage = b.add(Flow[ServiceInboundMessage].mapAsyncUnordered[ServiceOutboundMessage](100) {
      case m: Signal =>
        println(s"!>>> SIGNAL IN: $m, now: ${System.currentTimeMillis()} millis tm: " + (m.expireAt - System.currentTimeMillis()))
        m.expireAt - System.currentTimeMillis() match {
          case expireInMillis if expireInMillis > 0 =>
            Patterns.ask(signalPort, m, expireInMillis).recover {
              case t =>
                // TODO event
                println(s"!>>> EXPIRED!")
                SignalAckFailed(m.correlationId, m.subj, None)
            }.map {
              case t: ServiceOutboundMessage => t
              case t =>
                // TODO event
                SignalAckFailed(m.correlationId, m.subj, None)
            }
            case _ => Futures.successful(SignalAckFailed(m.correlationId, m.subj, None))
        }
//        case _ => Futures.

    })


    val merge = b.add(MergePreferred[ServiceOutboundMessage](1))

    val publisherSource = b.add(publisher)

    val ignore = Sink.ignore
    val router = b.add(new MessageRouter[ServiceInboundMessage])

    val monitor = b.add(Flow[ServiceInboundMessage].transform(() => lifecycleMonitor))

    monitor ~>  router.in
                router.out(0)   ~> requestSink // stream subscriptions
                router.out(1)   ~> signalExecStage ~> merge.preferred // signals
                                   publisherSource ~> merge

    FlowShape(monitor.inlet, merge.out)

  })

}
