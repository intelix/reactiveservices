package backend

import akka.actor._
import akka.stream._
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Tcp.OutgoingConnection
import backend.ConnectionManagerActor.Messages.{Connect, ConnectionAttemptFailed, SuccessfullyConnected}
import backend.ConnectionManagerActor._
import backend.DatasourceStreamLinkStage.LinkRef
import com.typesafe.scalalogging.StrictLogging
import rs.core.actors.ActorState
import rs.core.config.ConfigOps.wrap
import rs.core.services.{CompoundStreamIdTemplate, SimpleStreamIdTemplate, SimpleStreamId, StatefulServiceActor}
import rs.core.stream.DictionaryMapStreamState.Dictionary
import rs.core.{CompositeTopicKey, Subject, TopicKey}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}


object ConnectionManagerActor {

  case class Endpoint(host: String, port: Int)

  case class StateData(endpoints: Vector[Endpoint], datasourceLinkRef: Option[ActorRef] = None)

  case object Disconnected extends ActorState

  case object ConnectionPending extends ActorState

  case object Connected extends ActorState

  object Messages {

    case class SuccessfullyConnected(connection: OutgoingConnection)

    case class ConnectionAttemptFailed()

    case object Connect

    case object ExposeFlow

  }

}

class ConnectionManagerActor(id: String) extends StatefulServiceActor[StateData](id) with StrictLogging {

  implicit val sys = context.system
  implicit val ec = context.dispatcher
  implicit val dict = Dictionary("price", "source")

  object PingsStream extends SimpleStreamIdTemplate("pings")
  object PriceStream extends CompoundStreamIdTemplate[String]("ccy")

  var subscriptions: Set[Short] = Set() // TODO MOVE TO FRAMEWORK! - Ability to get active streams

  val endpoints = serviceCfg.asStringList("servers.enabled") map { id =>
    Endpoint(serviceCfg.asString(s"servers.$id.host", "localhost"), serviceCfg.asInt(s"servers.$id.port", 9100))
  }

  val decider: Supervision.Decider = {
    case x =>
      x.printStackTrace()
      Supervision.Stop
  }
  implicit val materializer =
    ActorMaterializer(ActorMaterializerSettings(context.system).withSupervisionStrategy(decider).withDebugLogging(enable = false))

  startWith(Disconnected, StateData(Vector() ++ endpoints))
  self ! Connect

  when(Disconnected) {
    case Event(Terminated(r), _) => stay()
    case Event(Connect, data) =>
      goto(ConnectionPending) using data.copy(endpoints = data.endpoints.tail :+ data.endpoints.head)
  }

  when(ConnectionPending) {
    case Event(SuccessfullyConnected(c), data) => goto(Connected)
    case Event(ConnectionAttemptFailed(), data) => goto(Disconnected)
  }

  when(Connected) {
    case Event(m@LinkRef(r), data) =>
      context.watch(r)
      subscriptions.foreach { ccy => r ! StreamRequest(ccy) }
      stay() using data.copy(datasourceLinkRef = Some(r))
    case Event(Kill, data) => data.datasourceLinkRef.foreach(_ ! KillServerRequest()); stay()
    case Event(m:Pong, data) => data.datasourceLinkRef.foreach(_ ! m); stay()
  }

  otherwise {
    case Event(Terminated(r), data) =>
      goto(Disconnected) using data.copy(datasourceLinkRef = None)
    case Event(m@LinkRef(r), data) =>
      stay() using data.copy(datasourceLinkRef = Some(context.watch(r)))
    case Event(PriceUpdate(ccyId, price, src), _) =>
      val streamId = PriceStream(ccyId.toString)
      streamId !#("price" -> price, "source" -> src)
      stay()
    case Event(Ping(id: Int), _) =>
      PingsStream() !~ id.toString
      stay()
    case Event(m@StreamRequest(ccy), data) =>
      subscriptions += ccy
      data.datasourceLinkRef.foreach{ r =>
        println(s"!>>>> request for $ccy -> $r")
        r ! m
      }
      stay()
    case Event(m@StreamCancel(ccy), data) =>
      subscriptions -= ccy
      data.datasourceLinkRef.foreach(_ ! m)
      stay()
  }

  onTransition {
    case ConnectionPending -> Disconnected =>
      log.info("Unable to connect to the datasource")
      setTimer("reconnectDelay", Connect, 1 second, repeat = false)
    case Connected -> Disconnected =>
      log.info("Lost connection to the datasource")
      self ! Connect
    case _ -> ConnectionPending =>
      val connectTo = stateData.endpoints.head
      log.info(s"Connecting to ${connectTo.host}:${connectTo.port}")

      println(s"!>>>> Connecting to ${connectTo.host}:${connectTo.port}")

      val flow = FramingStage() atop CodecStage() join DatasourceStreamLinkStage(self)

      Tcp().outgoingConnection(connectTo.host, connectTo.port) join flow run() onComplete {
        case Success(c) => self ! SuccessfullyConnected(c)
        case Failure(f) => self ! ConnectionAttemptFailed()
      }
    case _ -> Connected =>
      println("!>>>> Successfully connected")
      log.info("Successfully connected")
      stateData.datasourceLinkRef foreach { ref =>
        subscriptions.foreach { ccy => ref ! StreamRequest(ccy) }
      }
  }

  onSubjectMapping {
    case Subject(_, TopicKey("pings"), _) => PingsStream()
    case Subject(_, CompositeTopicKey("ccy", ccy), _) =>
      println(s"!>>>>> mapped for $ccy")
      PriceStream(ccy)
  }

  onStreamActive {
    case PriceStream(i) =>
      println(s"!>>>>> active: $i")
      self ! StreamRequest(i.toShort)
  }

  onStreamPassive {

    case PriceStream(i) => self ! StreamCancel(i.toShort)
  }

  onSignal {
    case (Subject(_, TopicKey("kill"), _), _) =>
      self ! KillServerRequest()
      SignalOk()
    case (Subject(_, TopicKey("pong"), _), data: String) =>
      println(s"!>>>> Received pong: $data")
//      self ! Pong(data.toInt)
      SignalOk()
  }



  override def componentId: String = "PricingEngine" // TODO move to event
}


