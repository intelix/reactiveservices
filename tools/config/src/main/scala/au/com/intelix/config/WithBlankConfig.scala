package au.com.intelix.config

import com.typesafe.config.ConfigFactory

trait WithBlankConfig extends WithConfig {

  lazy implicit override val config = ConfigFactory.empty()

}
