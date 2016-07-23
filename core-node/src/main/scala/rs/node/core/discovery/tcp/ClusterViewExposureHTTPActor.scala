package rs.node.core.discovery.tcp

import java.io.{FileInputStream, InputStream}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext}

import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import rs.core.actors.{ClusterAwareness, StatelessActor}
import rs.core.evt.EvtSource
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.Multipart.BodyPart
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ContentDispositionTypes, `Content-Disposition`}
import akka.http.scaladsl.server.Route
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging

import scala.util.Failure

class ClusterViewExposureHTTPActor(endpointHost: String, endpointPort: Int) extends StatelessActor with ClusterAwareness with StrictLogging {
  override val evtSource: EvtSource = "ClusterViewExposureHTTPActor"

  implicit val system = context.system
  implicit val executor = context.dispatcher

  private case class SuccessfulBinding(binding: Http.ServerBinding)

  private case class BindingFailed(x: Throwable)

  def resourceStream(resourceName: String): InputStream =
    new FileInputStream(resourceName)

  val httpsCtx: ConnectionContext = try {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    //    keyStore.load(resourceStream("/home/uat/ssl/keystore"), "keystore".toCharArray)
//    keyStore.load(resourceStream("/Users/maks/Desktop/work/sandbox/ssl/keystore"), "keystore".toCharArray)
    keyStore.load(resourceStream("/ssl/keystore"), "keystore".toCharArray)


    //    keyStore.load(resourceStream("w:/cygwin/home/maks/ssl/keystore"), "keystore".toCharArray)

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, "password".toCharArray)

    val ciphers = List(
      //      "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
      //      "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
      //      "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
      //      "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
      //      "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
      //      "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
      //      "TLS_RSA_WITH_AES_256_CBC_SHA",
      "TLS_RSA_WITH_AES_128_CBC_SHA",
      "SSL_RSA_WITH_3DES_EDE_CBC_SHA"
    )

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, null, new SecureRandom)
    ConnectionContext.https(sslContext = sslContext, enabledProtocols = Some(List("TLSv1", "TLSv1.1", "TLSv1.2")))
  } catch {
    case x: Throwable =>
      x.printStackTrace()
      ConnectionContext.noEncryption()
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

  Http().bindAndHandle(handler = flow, interface = "0.0.0.0", port = endpointPort,  connectionContext = httpsCtx) onComplete {
    case scala.util.Success(binding) => self ! SuccessfulBinding(binding)
    case Failure(x) => self ! BindingFailed(x)
  }



  private def buildMessage(): ByteString =
    TcpMessages.RemoteClusterView(reachableMembers.values.map(_.address.toString).toSet, clusterRoles).toByteString

}
