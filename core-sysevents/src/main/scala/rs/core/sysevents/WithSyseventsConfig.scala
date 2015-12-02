package rs.core.sysevents

import com.typesafe.config.ConfigFactory

trait WithSyseventsConfig {
  lazy val syseventsConfig = WithSyseventsConfig.config
}

private object WithSyseventsConfig {
  private lazy val configName: String = java.lang.System.getProperty("config-sysevents", "sysevents")
  lazy val config = if (configName == null) ConfigFactory.empty() else ConfigFactory.load(configName)
}
