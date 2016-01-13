package rs.core.evt


import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

object EvtSettings {
  lazy val config = ConfigRef.config
}

private object ConfigRef extends LazyLogging {

  private lazy val configName: String = {
    val cfg = java.lang.System.getProperty("evt-config", "evt")
    logger.info(s"Loading evt config from $cfg")
    cfg
  }
  lazy val config = if (configName == null) ConfigFactory.empty() else ConfigFactory.load(configName)
}
