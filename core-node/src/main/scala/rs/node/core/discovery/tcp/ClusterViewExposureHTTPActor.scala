package rs.node.core.discovery.tcp

import java.io.{File, FileInputStream, InputStream}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision, TLSClientAuth}
import au.com.intelix.rs.core.actors.{ClusterAwareness, StatelessActor}
import au.com.intelix.evt.EvtSource
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.Multipart.BodyPart
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ContentDispositionTypes, `Content-Disposition`}
import akka.http.scaladsl.server.Route
import akka.util.ByteString
import au.com.intelix.sslconfig._
import com.typesafe.scalalogging.StrictLogging
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import au.com.intelix.config.ConfigOps.wrap

import scala.util.Failure

class ClusterViewExposureHTTPActor(endpointHost: String, endpointPort: Int) extends StatelessActor
  with ClusterAwareness
  with StrictLogging
  with SSLConfig {
  override val evtSource: EvtSource = "ClusterViewExposureHTTPActor"

  implicit val system = context.system
  implicit val executor = context.dispatcher

  private case class SuccessfulBinding(binding: Http.ServerBinding)

  private case class BindingFailed(x: Throwable)


  val discoveryCfg = config.asConfig("node.cluster.discovery")

  override val sslConfigPath: String = "node.cluster.discovery.exposure.ssl"

  override def sslContextDefaults: SSLContextDefaults = SSLContextDefaults.Server

  lazy val connectionCtx: ConnectionContext = configuredSSLContext match {
    case FailedSSLContext(error) => throw new Error(error)
    case DisabledSSLContext => ConnectionContext.noEncryption()
    case InitialisedSSLContext(sslContext, protos, algos) =>
      ConnectionContext.https(
        sslContext = sslContext,
        enabledProtocols = Some(protos),
        enabledCipherSuites = algos,
        clientAuth = Some(TLSClientAuth.Need))
  }

  val decider: Supervision.Decider = {
    case x =>
      //      raise(EvtFlowFailure, 'error -> x, 'stacktrace -> x.getStackTrace)
      Supervision.Stop
  }
  implicit val mat = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      //      .withDebugLogging(serviceCfg.asBoolean("log.flow-debug", defaultValue = false))
      .withDebugLogging(true)
      .withInputBuffer(initialSize = 64, maxSize = 64)
      .withAutoFusing(false)
      .withFuzzing(false)
      .withSupervisionStrategy(decider))

  import akka.http.scaladsl.server.Directives._

  def flow: Route = {
    path("check") {
      complete(buildMessage().utf8String)
    }
  }

  Http().bindAndHandle(handler = flow, interface = "0.0.0.0", port = endpointPort,  connectionContext = connectionCtx) onComplete {
    case scala.util.Success(binding) => self ! SuccessfulBinding(binding)
    case Failure(x) => self ! BindingFailed(x)
  }



  private def buildMessage(): ByteString =
    TcpMessages.RemoteClusterView(reachableMembers.values.map(_.address.toString).toSet, clusterRoles).toByteString

}
