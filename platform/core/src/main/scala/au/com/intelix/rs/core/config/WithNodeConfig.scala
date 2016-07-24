package au.com.intelix.rs.core.config

import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.config.{CommonAttributes, RootConfig}

trait WithNodeConfig extends CommonAttributes {
  implicit val nodeCfg: RootConfig
  lazy val nodeId = nodeCfg.asString("node.id", "n/a")

  override def commonAttributes: Map[Symbol, Any] = Map('nodeid -> nodeId)
}
