package rs.node.core.discovery.tcp

import akka.actor.Status
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import rs.core.actors.{ActorState, StatefulActor}
import rs.core.evt.EvtSource
import rs.node.core.discovery.tcp.RegionEndpointConnectorActor.{States, _}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success}

object RegionEndpointConnectorActor {

  object In {

    case object Close

  }

  object Out {

    case class View(members: Set[String], roles: Set[String])

    case object Invalidate

  }


  //  case class Data(socket: Option[ActorRef] = None, buffer: ByteString = CompactByteString())
  case class MyData()

  private object States2 {

    case object Connecting extends ActorState

    case object WaitingBeforeReconnectAttempt extends ActorState

    case object Connected extends ActorState

    case object Closing extends ActorState

  }

  private object States {

    case object Idle extends ActorState

    case object Querying extends ActorState

    case object Closing extends ActorState

  }

}

class RegionEndpointConnectorActor(regionId: String, endpoint: Endpoint) extends StatefulActor[MyData] with StrictLogging {
  override val evtSource: EvtSource = "RegionEndpointConnectorActor"

  import akka.pattern.pipe
  import context.dispatcher

  case class Data(b: Option[ByteString])

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  val http = Http(context.system)

  startWith(States.Querying, MyData())

  when(States.Idle, stateTimeout = 5 seconds) {
    case Event(StateTimeout, _) => transitionTo(States.Querying)
    case Event(In.Close, _) => stop()
  }

  when(States.Querying) {
    case Event(In.Close, _) => transitionTo(States.Closing)
    case Event(Data(None), data) => transitionTo(States.Idle)
    case Event(Data(Some(bs)), data) =>
      bs match {
        case nextPkt@TcpMessages.RemoteClusterView(mem, roles) =>
          logger.info(s"!>>>> Received ${nextPkt.utf8String} from $endpoint")
          context.parent ! Out.View(mem, roles)
        case x =>
          logger.info("!>>>> Unrecognised: " + x.utf8String)
      }
      transitionTo(States.Idle)
  }

  when(States.Closing) {
    case Event(t: Data, _) => stop()
  }

  otherwise {
    case Event(HttpResponse(StatusCodes.OK, headers, entity, _), data) =>
      val data = entity.dataBytes.runFold(ByteString(""))(_ ++ _)
      data.onComplete {
        case Success(d) => self ! Data(Some(d))
        case Failure(e) =>
          logger.error("Hmm...", e)
          self ! Data(None)
      }
      stay()
    case Event(HttpResponse(_, _, _, _), _) =>
      self ! Data(None)
      stay()
    case Event(Status.Failure(e), _) =>
      self ! Data(None)
      stay()
  }

  onTransition {
    case _ -> States.Querying =>
      val uri = s"http://${endpoint.host}:${endpoint.port}/check"
      logger.info(s"!>>> GET: $uri")
      http.singleRequest(HttpRequest(uri = uri)).pipeTo(self)
  }
}

/*
class RegionEndpointConnectorActor2(regionId: String, endpoint: Endpoint) extends StatefulActor[Data] with StrictLogging {
  override val evtSource: EvtSource = s"RegionEndpointConnector.$regionId"

  import context.system

  val MaxPacketLen: Short = 20000
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  val manager = IO(Tcp)

  startWith(States.Connecting, Data())

  when(States.Connecting) {
    case Event(CommandFailed(_: Connect), _) => transitionTo(States.WaitingBeforeReconnectAttempt)
    case Event(Tcp.Connected(remote, local), data) =>
      sender() ! Tcp.Register(self)
      logger.info("!>>> Connected... ")
      transitionTo(States.Connected) using data.copy(socket = Some(sender()))
  }

  when(States.WaitingBeforeReconnectAttempt) {
    case Event(Internal.Reconnect, _) => transitionTo(States.Connecting)
  }

  when(States.Connected) {
    case Event(Tcp.Received(bs), data) =>
      var (pkts, remainder) = splitPacket(data.buffer ++ bs)
      pkts.foreach {
        case nextPkt@TcpMessages.RemoteClusterView(mem, roles) =>
          logger.info(s"!>>>> Received ${nextPkt.utf8String} from $endpoint")
          context.parent ! Out.View(mem, roles)
        case x =>
          logger.info("!>>>> Unrecognised: " + x.utf8String)
      }
      stay() using data.copy(buffer = remainder)
    case Event(_: Tcp.ConnectionClosed, _) =>
      logger.info("!>>> Connection broken... scheduling reconnect")
      context.parent ! Out.Invalidate
      transitionTo(States.Connecting) using Data()
  }

  when(States.Closing) {
    case Event(_: Tcp.ConnectionClosed, _) =>
      logger.info("Connection successfully stopped. Stopping now")
      stop()
    case Event(StateTimeout, data) =>
      logger.warn(s"Unable to close connection ${data.socket}")
      stop()
    case Event(m, data) =>
      logger.debug(s"Ignored $m in Closing state")
      stay()
  }

  otherwise {
    case Event(In.Close, data) if data.socket.isDefined =>
      logger.info("!>>> Received close instruction. Closing connection and stopping after.")
      data.socket.foreach(_ ! Tcp.Abort)
      transitionTo(States.Closing) forMax (1 minute)
    case Event(In.Close, _) =>
      logger.info("!>>> Received close instruction. Stopping")
      stop()
  }

  onTransition {
    case _ -> States.Connecting => manager ! Tcp.Connect(endpoint.addr)
    case _ -> States.WaitingBeforeReconnectAttempt => setTimer("reconnectTrigger", Internal.Reconnect, 5 seconds)
  }


  def splitPacket(data: ByteString): (List[ByteString], ByteString) = {

    val headerSize = 2

    @tailrec
    def split(packets: List[ByteString], current: ByteString): (List[ByteString], ByteString) = {
      if (current.length < headerSize) {
        (packets.reverse, current)
      } else {
        val len = current.iterator.getShort
        if (len > MaxPacketLen || len < 0) return (List(), ByteString.empty)
        if (current.length < len + headerSize) {
          (packets.reverse, current)
        } else {
          val rem = current drop headerSize
          val (front, back) = rem.splitAt(len.toInt)
          split(front :: packets, back)
        }
      }
    }
    split(List[ByteString](), data)
  }


  object Internal {

    case object Reconnect

  }

} */
