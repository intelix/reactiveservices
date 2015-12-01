package rs.core.config

import com.typesafe.config.ConfigFactory

trait WithExternalConfig {

  implicit val globalConfig: GlobalConfig = GlobalConfig(ExternalConfig.config)

}

private object ExternalConfig {
  private lazy val configName: String = java.lang.System.getProperty("config", System.getProperty("default-config", "default.conf"))
  lazy val config = if (configName == null) ConfigFactory.empty() else ConfigFactory.load(configName)
}
