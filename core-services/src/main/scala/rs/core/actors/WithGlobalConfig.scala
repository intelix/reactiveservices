package rs.core.actors

import rs.core.config.ConfigOps.wrap
import rs.core.config.GlobalConfig
import rs.core.sysevents.WithSyseventPublisher

trait WithGlobalConfig extends WithSyseventPublisher with ActorUtils {


  implicit val globalCfg = GlobalConfig(config)

  lazy val nodeId = globalCfg.asString("node.id", "n/a")


  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('nodeid -> nodeId)


}
