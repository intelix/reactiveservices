package rs.core.config

import rs.core.config.ConfigOps.wrap

trait WithNodeConfig {
  implicit val nodeCfg: NodeConfig
  lazy val nodeId = nodeCfg.asString("node.id", "n/a")
}
