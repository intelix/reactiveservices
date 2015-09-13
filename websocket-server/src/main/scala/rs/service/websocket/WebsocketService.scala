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
package rs.service.websocket

import java.util.concurrent.atomic.AtomicInteger

import akka.http.javadsl.model.HttpMethods
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, UpgradeToWebsocket}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.scaladsl.BidiFlow
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.util.ByteString
import rs.core.actors.BaseActorSysevents
import rs.core.codec.binary.BinaryProtocolMessages.{BinaryDialectInbound, BinaryDialectOutbound}
import rs.core.codec.binary._
import rs.core.config.ConfigOps.wrap
import rs.core.services.Messages.{ServiceInbound, ServiceOutbound}
import rs.core.services.ServiceCell
import rs.core.services.endpoint.akkastreams._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

trait WebsocketServiceSysevents extends BaseActorSysevents {
  val ServerOpen = "ServerOpen".info
  val UnableToBind = "UnableToBind".error
  val NewConnection = "NewConnection".info
  val FlowFailure = "FlowFailure".error

  override def componentId: String = "Service.WebsocketServer"
}

class WebsocketService(id: String) extends ServiceCell(id) with WebsocketServiceSysevents {

  object WSRequest {
    def unapply(req: HttpRequest): Option[(UpgradeToWebsocket, HttpRequest)] = {
      if (req.header[UpgradeToWebsocket].isDefined) {
        req.header[UpgradeToWebsocket] match {
          case Some(upgrade) => Some(upgrade, req)
          case None => None
        }
      } else None
    }
  }

  implicit val system = context.system

  val port = serviceCfg.asInt("endpoint-port", 8080)
  val host = serviceCfg.asString("endpoint-host", "localhost")
  val bindingTimeout = serviceCfg.asFiniteDuration("start-timeout", 3 seconds)

  val bytesStagesList = serviceCfg.asClassesList("stage-bytes")
  val protocolDialectStagesList = serviceCfg.asClassesList("stage-protocol-dialect")
  val serviceDialectStagesList = serviceCfg.asClassesList("stage-service-dialect")


  val decider: Supervision.Decider = {
    case x =>
      FlowFailure('error -> x)
      Supervision.Stop
  }

  import rs.core.codec.binary.BinaryCodec.DefaultCodecs._

  implicit val mat = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withDebugLogging(serviceCfg.asBoolean("log.flow-debug", defaultValue = false))
      .withSupervisionStrategy(decider))

  implicit val executor = context.dispatcher

  def handleWebsocket(upgrade: UpgradeToWebsocket, id: String) = NewConnection { ctx =>
    ctx + ('token -> id)
    upgrade.handleMessages(buildFlow(id))
  }

  def buildFlow(id: String) = {

    println(s"!>>> $serviceDialectStagesList")

    val bytesStage = bytesStagesList.foldLeft[BidiFlow[Message, ByteString, ByteString, Message, Unit]](WebsocketBinaryFrameFolder.buildStage(id, componentId)) {
      case (flow, builder) => flow atop builder.asInstanceOf[BytesStageBuilder].buildStage(id, componentId)
    }
    val protocolDialectStage = protocolDialectStagesList.foldLeft[BidiFlow[ByteString, BinaryDialectInbound, BinaryDialectOutbound, ByteString, Unit]](BinaryCodec.Streams.buildServerSideSerializer(id, componentId)) {
      case (flow, builder) => flow atop builder.asInstanceOf[BinaryDialectStageBuilder].buildStage(id, componentId)
    }
    val serviceDialectStage = serviceDialectStagesList.foldLeft[BidiFlow[BinaryDialectInbound, ServiceInbound, ServiceOutbound, BinaryDialectOutbound, Unit]](BinaryCodec.Streams.buildServerSideTranslator(id, componentId)) {
      case (flow, builder) => flow atop builder.asInstanceOf[ServiceDialectStageBuilder].buildStage(id, componentId)
    }

    bytesStage atop protocolDialectStage atop serviceDialectStage join ServicePort.buildFlow(id)
  }


  var connectionCounter = new AtomicInteger(0)

  Http().bindAndHandleSync(handler = {
    case WSRequest(upgrade, HttpRequest(HttpMethods.GET, Uri.Path("/"), _, _, _)) =>
      val count = connectionCounter.incrementAndGet()
      val id = count + "_" + shortUUID
      handleWebsocket(upgrade, id)

    case r: HttpRequest =>
      Invalid('uri -> r.uri.path.toString(), 'method -> r.method.name)
      HttpResponse(400, entity = "Invalid websocket request")
  }, interface = host, port = port) onComplete {
    case Success(binding) => self ! SuccessfulBinding(binding)
    case Failure(x) => self ! BindingFailed(x)
  }

  onMessage {
    case SuccessfulBinding(binding) =>
      ServerOpen('host -> host, 'port -> port, 'address -> binding.localAddress)
    case BindingFailed(x) =>
      UnableToBind('timeoutMs -> bindingTimeout.toMillis, 'error -> x)
      context.stop(self)
  }

  private case class SuccessfulBinding(binding: Http.ServerBinding)

  private case class BindingFailed(x: Throwable)

}
