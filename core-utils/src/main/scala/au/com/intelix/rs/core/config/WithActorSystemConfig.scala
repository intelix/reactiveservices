package au.com.intelix.rs.core.config

import akka.actor.Actor
import au.com.intelix.config.{RootConfig, WithConfig}

trait WithActorSystemConfig extends WithConfig {
  self: Actor =>

  lazy implicit override val config = context.system.settings.config
  lazy val rootConfig: RootConfig = RootConfig(config)
}
