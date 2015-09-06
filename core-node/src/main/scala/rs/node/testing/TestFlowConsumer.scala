package rs.node.testing

import java.util.concurrent.atomic.AtomicReference

import akka.pattern.Patterns
import akka.stream._
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl._
import rs.core.actors.{ActorWithComposableBehavior, ActorWithTicks}
import rs.core.services.Messages.{CloseSubscription, OpenSubscription, Signal, StreamStateUpdate}
import rs.core.services.endpoint.akkastreams.{ServicePortSubscriptionRequestSinkSubscriber, ServicePortStreamSource}
import rs.core.services.internal.{SignalPort, StreamAggregatorActor}
import rs.core.stream.StringStreamState
import rs.core.{ServiceKey, Subject, TopicKey}

import scala.concurrent.duration._
import scala.language.postfixOps


class Ticks extends ActorWithComposableBehavior with ActorPublisher[Any] with ActorWithTicks {

  var cnt = 0

  override def tickInterval: FiniteDuration = 500 millis

  override def processTick(): Unit = {
    cnt += 1
    if (totalDemand > 0 && cnt < 1000)
      onNext(Signal(Subject("test-publisher", "string", ""), "hey tick " + cnt, now + 10000, None, None))
    super.processTick()
  }

  override def componentId: String = "Ticks"
}

class TestFlowConsumer extends ActorWithComposableBehavior {
  override def componentId: String = "TestFlowConsumer"


  val authorisation = BidiFlow() { b =>

    val permissions: AtomicReference[String] = new AtomicReference[String]("")

    def extractPermissions(m: Any): Any = m match {
      case x@StreamStateUpdate(Subject(ServiceKey("test-publisher"), TopicKey("permissions"), _), StringStreamState(st)) =>
        logger.info("!>>>>> PERMISSIONS: " + st)
        permissions.set(st)
        x
      case x => x
    }

    def checkPermissions(m: Any): Boolean = m match {
      case x =>
        logger.info(s"!>>>>> flow: Checking permissions for $x")
        true
    }

    val outbound = b.add(Flow[Any].map(extractPermissions))
    val inbound = b.add(Flow[Any].filter(checkPermissions))

    BidiShape(inbound, outbound)
  }

  val authentication = BidiFlow() { b =>

    val token: AtomicReference[String] = new AtomicReference[String]("")

    def extractToken(m: Any): Any = m match {
      case x@StreamStateUpdate(Subject(ServiceKey("test-publisher"), TopicKey("token"), _), StringStreamState(st)) =>
        logger.info("!>>>>> TOKEN: " + st)
        token.set(st)
        x
      case x => x
    }

    def removeKeys(m: Any): Any = m match {
      case x: StreamStateUpdate => x.copy(subject = x.subject.copy(keys = ""))
      case x => x
    }

    def addkeys(m: Any): Any = m match {
      case x: OpenSubscription =>
        logger.info(s"!>>>>> flow: adding keys to $x")

        x.copy(subj = x.subj.copy(keys = "token:" + token.get()))
      case x: CloseSubscription =>
        logger.info(s"!>>>>> flow: adding keys to $x")

        x.copy(subj = x.subj.copy(keys = "token:" + token.get()))
      case x => x
    }

    val outbound = b.add(Flow[Any].map(extractToken).map(removeKeys))
    val inbound = b.add(Flow[Any].map(addkeys))

    BidiShape(inbound, outbound)
  }


  val flow = FlowGraph.partial() { implicit b =>

    import FanOutShape._
    import FlowGraph.Implicits._


    class MessageRouterShape[T](_init: Init[T] = Name[T]("Router"))
      extends FanOutShape[T](_init) {
      val outEvents = newOutlet[T]("eventsOut")
      val outSignals = newOutlet[T]("signalsOut")
      val outSubReq = newOutlet[T]("subreqOut")

      override protected def construct(init: Init[T]): FanOutShape[T] = new MessageRouterShape()
    }

    class MessageRouter[T] extends FlexiRoute[T, UniformFanOutShape[T, T]](new UniformFanOutShape(3), Attributes.name("Router")) {

      import FlexiRoute._

      override def createRouteLogic(s: PortT): RouteLogic[T] = new RouteLogic[T] {
        override def initialState: State[Unit] =
          State(DemandFromAll(s.out(0), s.out(1), s.out(2))) {
            (ctx, _, el) =>
              el match {
                case x: OpenSubscription => ctx.emit(s.out(1))(el)
                case x: CloseSubscription => ctx.emit(s.out(1))(el)
                case x: Signal => ctx.emit(s.out(2))(el)
                case x =>
              }
              SameState
          }

        override def initialCompletionHandling: CompletionHandling = CompletionHandling(
          onUpstreamFailure = (ctx, cause) => ctx.finish(),
          onUpstreamFinish = ctx => ctx.finish(),
          onDownstreamFinish = (ctx, _) => {
            ctx.finish(); SameState
          }
        )
      }


    }







    val aggregator = context.actorOf(StreamAggregatorActor.props(), "aggregator")
    val signalPort = context.actorOf(SignalPort.props, "signalport")

    val publisher = Source.actorPublisher(ServicePortStreamSource.props(aggregator))

    val requestSink = b.add(Sink.actorSubscriber(ServicePortSubscriptionRequestSinkSubscriber.props(aggregator)))

    //    val signalTicksSource = b.add(Source.actorPublisher(Props[Ticks]))

    //    val bytesToRequests = b.add(Flow[ByteString].mapConcat[Request](bytesToRequestMap))
    //    val execRequests = b.add(Flow[Request].mapConcat[Response](requestProcessor))
    //
    //    val responsesToBytes = b.add(Flow[Response].map[ByteString](responseToBytesMap))

    val responsesToX = b.add(Flow[Any].map { x =>
      logger.info("!>>>> response: " + x)
      x
    })

    val xToRequest = b.add(Flow[Any].map { x =>
      logger.info("!>>>> request: " + x)
      x
    })

    val signalExecStage = b.add(Flow[Any].mapAsyncUnordered(100) {
      case m: Signal => Patterns.ask(signalPort, m, m.expireAt - now)
    })

    val merge = b.add(MergePreferred[Any](1))

    val publisherSource = b.add(publisher)

    //    xToRequest ~> requestSink

    val ignore = Sink.ignore
    val router = b.add(new MessageRouter[Any])

    xToRequest ~> router.in
    router.out(0) ~> ignore // events
    router.out(1) ~> requestSink // stream subscriptions
    router.out(2) ~> signalExecStage ~> merge.preferred // signals
    publisherSource ~> merge ~> responsesToX

    //    bytesToRequests ~> execRequests ~> merge
    //    publisherSource ~> merge ~> responsesToBytes



    FlowShape(xToRequest.inlet, responsesToX.outlet)

  }

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(context.system).withInputBuffer(initialSize = 64, maxSize = 64))

  val requestSource = Source(List(
    OpenSubscription(Subject("test-publisher", "token", "")),
    OpenSubscription(Subject("test-publisher", "permissions", "")),
    OpenSubscription(Subject("test-publisher", "string", ""))
  ))

  val stack = authorisation.atop(authentication)
  val stackFlow = stack.join(Flow.wrap(flow))


  requestSource.via(Flow[Any].map { x =>
    logger.info(s"!>>>>>>>> Flow in: $x")
    x
  }).via(stackFlow).runWith(Sink.foreach { x =>
    logger.info(s"!>>>>>>>> Flow out: $x")
  })


}
