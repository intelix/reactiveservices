package rs.core.config

import com.typesafe.config.Config

case class ServiceConfig(override val config: Config) extends WithConfig
