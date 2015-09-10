package rs.core.config

import com.typesafe.config.Config

trait WithConfig {
  def config: Config
}
