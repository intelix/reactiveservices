package rs.core.config

import akka.actor.Actor

trait WithActorSystemConfig extends WithSomeConfig {
  self: Actor =>

  lazy implicit override val config = context.system.settings.config

}
