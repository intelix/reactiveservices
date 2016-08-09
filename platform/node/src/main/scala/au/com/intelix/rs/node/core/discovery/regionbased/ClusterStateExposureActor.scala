package au.com.intelix.rs.node.core.discovery.regionbased

import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision, TLSClientAuth}
import akka.util.ByteString
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.rs.core.actors.{ClusterAwareness, StatelessActor}
import au.com.intelix.sslconfig._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import ClusterStateExposureActor._
import au.com.intelix.evt.{ErrorE, InfoE, TraceE, WarningE}

object ClusterStateExposureActor {
  object Evt {
    case object FlowTerminated extends TraceE
    case object StatusSent extends TraceE
    case object Denied_MissingToken extends WarningE
    case object Denied_TokenMismatch extends WarningE
    case object Mode_Blocked extends TraceE
    case object Mode_Unblocked extends TraceE
    case object ServiceOpen extends InfoE
    case object UnableToBind extends ErrorE
    case object ServiceClosed extends InfoE
    case object TerminatingListener extends InfoE
  }
}

class ClusterStateExposureActor(endpointHost: String, endpointPort: Int) extends StatelessActor with ClusterAwareness {

  implicit val system = context.system
  implicit val executor = context.dispatcher

  object Internal {
    case class SuccessfulBinding(binding: Http.ServerBinding)
    case class BindingFailed(x: Throwable)
    case object Bind
  }


  var blocked = false

  commonEvtFields('endpoint -> s"$endpointHost:$endpointPort")


  val discoveryCfg = config.asConfig("node.cluster.discovery.region-based-http")

  val apiKey = discoveryCfg.asString("api-key", "none")

  lazy val connectionCtx: ConnectionContext =
    SSLContextHelper.build(discoveryCfg.asConfig("exposure.ssl"), SSLContextDefaults.Server) match {
      case FailedSSLContext(error) => throw new Error(error)
      case DisabledSSLContext => ConnectionContext.noEncryption()
      case InitialisedSSLContext(sslContext, protos, algos) =>
        ConnectionContext.https(
          sslContext = sslContext,
          enabledProtocols = Some(protos),
          enabledCipherSuites = algos,
          clientAuth = Some(TLSClientAuth.Need))
    }

  val decider: Supervision.Decider = { x =>
    evt(Evt.FlowTerminated, 'error -> x, 'stacktrace -> x.getStackTrace)
    Supervision.Stop
  }

  implicit val mat = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withDebugLogging(true)
      .withInputBuffer(initialSize = 2, maxSize = 16)
      .withAutoFusing(false)
      .withFuzzing(false)
      .withSupervisionStrategy(decider))

  import akka.http.scaladsl.server.Directives._

  self ! Internal.Bind

  def flow: Route = {
    path("api" / "cluster" / "state") {
      optionalHeaderValueByName("Api-Key") {
        case Some(token) if token == apiKey =>
          if (blocked) complete((StatusCodes.BadRequest, "Blocked"))
          else {
            val x = buildMessage().utf8String
            evt(Evt.StatusSent, 'status -> x)
            complete(x)
          }
        case Some(token) =>
          val ref = generateUUID
          evt(Evt.Denied_TokenMismatch, 'provided -> token, 'ref -> ref)
          complete((StatusCodes.Forbidden, s"Forbidden, ref $ref"))
        case _ =>
          val ref = generateUUID
          evt(Evt.Denied_MissingToken, 'ref -> ref)
          complete((StatusCodes.Forbidden, s"Forbidden, ref $ref"))
      }
    }
  }

  var binding: Option[ServerBinding] = None

  onMessage {
    case Internal.Bind =>
      Http().bindAndHandle(handler = flow, interface = endpointHost, port = endpointPort, connectionContext = connectionCtx) onComplete {
        case scala.util.Success(b) => self ! Internal.SuccessfulBinding(b)
        case Failure(x) => self ! Internal.BindingFailed(x)
      }
    case t: TrafficBlocking.BlockCommunicationWith =>
      evt(Evt.Mode_Blocked)
      blocked = true
    case t: TrafficBlocking.UnblockCommunicationWith =>
      evt(Evt.Mode_Unblocked)
      blocked = false
    case Internal.SuccessfulBinding(b) =>
      evt(Evt.ServiceOpen)
      binding = Some(b)
    case Internal.BindingFailed(b) =>
      evt(Evt.UnableToBind, 'error -> b)
  }

  override def postStop(): Unit = {
    binding.foreach { x =>
      evt(Evt.TerminatingListener)
      Await.result(x.unbind(), 5 seconds)
      evt(Evt.ServiceClosed)
    }
    super.postStop()
  }

  private def buildMessage(): ByteString =
    RemoteClusterView(allMembers.values.map(_.address.toString).toSet, clusterRoles).toByteString

}
