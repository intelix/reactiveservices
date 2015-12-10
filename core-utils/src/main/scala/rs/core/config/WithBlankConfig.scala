package rs.core.config

import com.typesafe.config.ConfigFactory

trait WithBlankConfig extends WithSomeConfig {

  lazy implicit override val config = ConfigFactory.empty()

}
