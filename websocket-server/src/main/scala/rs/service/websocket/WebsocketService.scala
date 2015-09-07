package rs.service.websocket

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import akka.http.javadsl.model.HttpMethods
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, UpgradeToWebsocket}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import org.jboss.netty.handler.codec.socks.SocksMessage.AuthStatus
import rs.core.ServiceKey
import rs.core.actors.BaseActorSysevents
import rs.core.codec.binary.{ByteStringAggregator, BinaryCodec, PartialUpdatesProducer, PingInjector}
import rs.core.services.ServiceCell
import rs.core.services.endpoint.akkastreams._
import rs.service.auth.AuthStage

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

trait WebsocketServiceSysevents extends BaseActorSysevents {
  val ServerOpen = "ServerOpen".info
  val UnableToBind = "UnableToBind".error
  val NewConnection = "NewConnection".info
  val FlowFailure = "FlowFailure".error

  override def componentId: String = "Service.WebsocketServer"
}

class WebsocketService(id: String) extends ServiceCell(id) with WebsocketServiceSysevents {

  object WSRequest {

    def unapply(req: HttpRequest): Option[HttpRequest] = {
      if (req.header[UpgradeToWebsocket].isDefined) {
        req.header[UpgradeToWebsocket] match {
          case Some(upgrade) => Some(req)
          case None => None
        }
      } else None
    }

  }

  implicit val system = context.system
  val decider: Supervision.Decider = {
    case x =>
      FlowFailure()
      Supervision.Stop
  }

  import rs.core.codec.binary.BinaryCodec.DefaultCodecs._
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(context.system).withDebugLogging(true).withSupervisionStrategy(decider))

  def handleWebsocket(req: HttpRequest, flow: Flow[Message, Message, Unit], id: String) = NewConnection { ctx =>
    ctx + ('token -> id)
    req.header[UpgradeToWebsocket].get.handleMessages(flow)
  }

  def echoFlow: Flow[Message, Message, Unit] = Flow[Message]

  def mainFlow(id: String) = {
    WebsocketBinaryFrameFolder.buildStage() atop
      ByteStringAggregator.buildStage(maxMessages = 100, within = 100 millis) atop
      BinaryCodec.Streams.buildServerSideSerializer() atop
      PingInjector.buildStage(30 seconds) atop
      PartialUpdatesProducer.buildStage() atop
      BinaryCodec.Streams.buildServerSideTranslator() atop
      AuthStage.buildStage(id, componentId) join
      ServicePort.buildFlow(id)
  }

  val port = 8080
  val interface = "localhost"
  val timeout = 2 seconds

  var connectionCounter = new AtomicInteger(0)

  val binding = Http().bindAndHandleSync(handler = {
    case WSRequest(req@HttpRequest(HttpMethods.GET, Uri.Path("/"), _, _, _)) =>
      val count = connectionCounter.incrementAndGet()
      val id = count + "_" + shortUUID
      handleWebsocket(req, mainFlow(id), id)

    case r: HttpRequest =>
      Invalid('uri -> r.uri.path.toString(), 'method -> r.method.name)
      HttpResponse(400, entity = "Invalid websocket request")
  }, interface = interface, port = port)

  try {
    Await.result(binding, timeout)
    ServerOpen('host -> interface, 'port -> port)
  } catch {
    case exc: TimeoutException =>
      UnableToBind('timeoutMs -> timeout.toMillis)
  }


}
