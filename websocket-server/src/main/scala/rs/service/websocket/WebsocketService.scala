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
package rs.service.websocket

import java.util.concurrent.atomic.AtomicInteger

import akka.http.javadsl.model.HttpMethods
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, UpgradeToWebsocket}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, Uri}
import akka.stream.scaladsl.BidiFlow
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.util.ByteString
import rs.core.codec.binary.BinaryProtocolMessages.{BinaryDialectInbound, BinaryDialectOutbound}
import rs.core.codec.binary._
import rs.core.config.ConfigOps.wrap
import rs.core.evt.{EvtSource, ErrorE, InfoE}
import rs.core.services.Messages.{ServiceInbound, ServiceOutbound}
import rs.core.services.StatelessServiceActor
import rs.core.services.endpoint.akkastreams._
import rs.core.sysevents.CommonEvt

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object WebsocketService {

  case object EvtServerOpen extends InfoE

  case object EvtUnableToBind extends ErrorE

  case object EvtNewConnection extends InfoE

  case object EvtFlowFailure extends ErrorE

  val EvtSourceId = "Service.WebsocketServer"
}

class WebsocketService(id: String) extends StatelessServiceActor(id) {

  import WebsocketService._

  private case class SuccessfulBinding(binding: Http.ServerBinding)

  private case class BindingFailed(x: Throwable)


  implicit val system = context.system
  implicit val executor = context.dispatcher

  val port = serviceCfg.asInt("endpoint-port", 8080)
  val host = serviceCfg.asString("endpoint-host", "localhost")
  val bindingTimeout = serviceCfg.asFiniteDuration("port-bind-timeout", 3 seconds)
  val bytesStagesList = serviceCfg.asClassesList("stage-bytes")
  val protocolDialectStagesList = serviceCfg.asClassesList("stage-protocol-dialect")
  val serviceDialectStagesList = serviceCfg.asClassesList("stage-service-dialect")

  val decider: Supervision.Decider = {
    case x =>
      raise(EvtFlowFailure, 'error -> x, 'stacktrace -> x.getStackTrace)
      Supervision.Stop
  }

  implicit val mat = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withDebugLogging(serviceCfg.asBoolean("log.flow-debug", defaultValue = false))
      .withSupervisionStrategy(decider))

  import rs.core.codec.binary.BinaryCodec.DefaultBinaryCodecImplicits._

  var connectionCounter = new AtomicInteger(0)

  def handleWebsocket(upgrade: UpgradeToWebsocket, headers: Seq[HttpHeader], id: String) = {
    raise(EvtNewConnection, 'token -> id, 'headers -> headers.map(_.toString))
    upgrade.handleMessages(buildFlow(id))
  }

  def buildFlow(id: String) = {

    val bytesStage = bytesStagesList.foldLeft[BidiFlow[Message, ByteString, ByteString, Message, Unit]](WebsocketBinaryFrameFolder.buildStage(id, EvtSourceId)) {
      case (flow, builder) =>
        builder.newInstance().asInstanceOf[BytesStageBuilder].buildStage(id, EvtSourceId) match {
          case Some(stage) => flow atop stage
          case None => flow
        }
    }
    val protocolDialectStage = protocolDialectStagesList.foldLeft[BidiFlow[ByteString, BinaryDialectInbound, BinaryDialectOutbound, ByteString, Unit]](BinaryCodec.Streams.buildServerSideSerializer(id, EvtSourceId)) {
      case (flow, builder) =>
        builder.newInstance().asInstanceOf[BinaryDialectStageBuilder].buildStage(id, EvtSourceId) match {
          case Some(stage) => flow atop stage
          case None => flow
        }
    }
    val serviceDialectStage = serviceDialectStagesList.foldLeft[BidiFlow[BinaryDialectInbound, ServiceInbound, ServiceOutbound, BinaryDialectOutbound, Unit]](BinaryCodec.Streams.buildServerSideTranslator(id, EvtSourceId)) {
      case (flow, builder) =>
        builder.newInstance().asInstanceOf[ServiceDialectStageBuilder].buildStage(id, EvtSourceId) match {
          case Some(stage) => flow atop stage
          case None => flow
        }
    }

    bytesStage atop protocolDialectStage atop serviceDialectStage join ServicePort.buildFlow(id)
  }


  Http().bindAndHandleSync(handler = {
    case WSRequest(upgrade, HttpRequest(HttpMethods.GET, Uri.Path("/"), headers, entity, proto)) =>
      val count = connectionCounter.incrementAndGet()
      val id = count + "_" + randomUUID
      handleWebsocket(upgrade, headers, id)

    case r: HttpRequest =>
      raise(CommonEvt.EvtInvalid, 'uri -> r.uri.path.toString(), 'method -> r.method.name)
      HttpResponse(400, entity = "Invalid websocket request")
  }, interface = host, port = port) onComplete {
    case Success(binding) => self ! SuccessfulBinding(binding)
    case Failure(x) => self ! BindingFailed(x)
  }

  onMessage {
    case SuccessfulBinding(binding) =>
      raise(EvtServerOpen, 'host -> host, 'port -> port, 'address -> binding.localAddress)
    case BindingFailed(x) =>
      raise(EvtUnableToBind, 'timeoutMs -> bindingTimeout.toMillis, 'error -> x)
      context.stop(self)
  }

  object WSRequest {
    def unapply(req: HttpRequest): Option[(UpgradeToWebsocket, HttpRequest)] = {
      if (req.header[UpgradeToWebsocket].isDefined) {
        req.header[UpgradeToWebsocket] match {
          case Some(upgrade) => Some((upgrade, req))
          case None => None
        }
      } else None
    }
  }

  override val evtSource: EvtSource = EvtSourceId
}
