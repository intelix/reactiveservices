package au.com.intelix.sslconfig

import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.essentials.resources.ResourcesUtil.loadResource
import com.typesafe.config.Config

sealed trait MaybeSSLContext

case object DisabledSSLContext extends MaybeSSLContext

case class InitialisedSSLContext(ctx: SSLContext, enabledProtocols: List[String], enabledAlgorithms: Option[List[String]]) extends MaybeSSLContext

case class FailedSSLContext(error: Throwable) extends MaybeSSLContext

case class SSLContextDefaults(
                               sslEnabled: Boolean = false,
                               keyStoreName: String,
                               keyStorePassword: String,
                               keyPassword: String,
                               trustStoreName: String,
                               trustStorePassword: String,
                               enabledProtocols: List[String] = List("TLSv1.2"),
                               enabledAlgorithms: Option[List[String]] = None
                             )

object SSLContextDefaults {
  val Server = SSLContextDefaults(
    keyStoreName = "server-keystore.jks",
    keyStorePassword = "",
    keyPassword = "",
    trustStoreName = "client-ca-trust.jks",
    trustStorePassword = "",
    enabledAlgorithms = Some(List("TLS_RSA_WITH_AES_128_CBC_SHA"))
  )
  val Client = SSLContextDefaults(
    keyStoreName = "client-keystore.jks",
    keyStorePassword = "",
    keyPassword = "",
    trustStoreName = "server-ca-trust.jks",
    trustStorePassword = "",
    enabledAlgorithms = Some(List("TLS_RSA_WITH_AES_128_CBC_SHA"))
  )
}

object SSLContextHelper {
  def build(sslCfg: Config, sslContextDefaults: SSLContextDefaults): MaybeSSLContext = {

    val defaults = sslContextDefaults
    val sslEnabled = sslCfg.asBoolean("enable-ssl", defaultValue = sslContextDefaults.sslEnabled)

    if (sslEnabled) {
      val keyStoreName = sslCfg.asString("security.key-store", defaults.keyStoreName)
      val keyStorePassword = sslCfg.asString("security.key-store-password", defaults.keyStorePassword)
      val keyPassword = sslCfg.asString("security.key-password", defaults.keyPassword)
      val trustStoreName = sslCfg.asString("security.trust-store", defaults.trustStoreName)
      val trustStorePassword = sslCfg.asString("security.trust-store-password", defaults.trustStorePassword)

      val protocols = sslCfg.asStringList("security.protocol") match {
        case Nil => defaults.enabledProtocols
        case x => x
      }
      var enabledAlgorithms = sslCfg.asStringList("security.enabled-algorithms") match {
        case Nil => defaults.enabledAlgorithms
        case x => Some(x)
      }

      try {

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
        keyStore.load(loadResource(keyStoreName).get, keyStorePassword.toCharArray)

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
        trustStore.load(loadResource(trustStoreName).get, trustStorePassword.toCharArray)

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        keyManagerFactory.init(keyStore, keyPassword.toCharArray)

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
        trustManagerFactory.init(trustStore)

        val sslContext = SSLContext.getInstance("TLS")

        sslContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)

        InitialisedSSLContext(sslContext, protocols, enabledAlgorithms)

      } catch {
        case e: Throwable => FailedSSLContext(e)
      }

    } else DisabledSSLContext
  }

}

