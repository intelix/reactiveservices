package rs.core.config

import com.typesafe.config.Config

case class GlobalConfig(override val config: Config) extends WithConfig
