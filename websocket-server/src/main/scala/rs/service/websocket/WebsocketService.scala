package rs.service.websocket

import java.util.concurrent.TimeoutException

import akka.http.javadsl.model.HttpMethods
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, UpgradeToWebsocket}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import org.jboss.netty.handler.codec.socks.SocksMessage.AuthStatus
import rs.core.ServiceKey
import rs.core.codec.binary.{ByteStringAggregator, BinaryCodec, PartialUpdatesProducer, PingInjector}
import rs.core.services.ServiceCell
import rs.core.services.endpoint.akkastreams._
import rs.service.auth.AuthStage

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class WebsocketService(id: String) extends ServiceCell(id) {

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
      x.printStackTrace()
      logger.error(s"!>>>>> ON $x ")
      Supervision.Stop
  }

  import rs.core.codec.binary.BinaryCodec.DefaultCodecs._
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(context.system).withDebugLogging(true).withSupervisionStrategy(decider))

  // binding is a future, we assume it's ready within a second or timeout
  def handleWith(req: HttpRequest, flow: Flow[Message, Message, Unit]) = {
    logger.info("!>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> new connection")
    req.header[UpgradeToWebsocket].get.handleMessages(flow)
  }

  def echoFlow: Flow[Message, Message, Unit] = Flow[Message]

  def mainFlow = {
    val id = shortUUID
    WebsocketBinaryFrameFolder.buildStage() atop
      ByteStringAggregator.buildStage(maxMessages = 100, within = 100 millis) atop
      BinaryCodec.Streams.buildServerSideSerializer() atop
      PingInjector.buildStage(30 seconds) atop
      PartialUpdatesProducer.buildStage() atop
      BinaryCodec.Streams.buildServerSideTranslator() atop
      AuthStage.buildStage(id) join
      EndpointFlow.buildFlow(id)
  }

  val binding = Http().bindAndHandleSync(handler = {
    case WSRequest(req@HttpRequest(HttpMethods.GET, Uri.Path("/"), _, _, _)) => handleWith(req, mainFlow)
    case _: HttpRequest => HttpResponse(400, entity = "Invalid websocket request")
  }, interface = "localhost", port = 8080)

  try {
    Await.result(binding, 1 second)
    println("!>>>>> Server online at http://localhost:9001")
  } catch {
    case exc: TimeoutException =>
      println("Server took to long to startup, shutting down")
      system.shutdown()
  }


}
