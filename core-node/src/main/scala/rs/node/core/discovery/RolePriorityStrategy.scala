package rs.node.core.discovery

import rs.core.config.ConfigOps.wrap
import rs.core.config.GlobalConfig

class RolePriorityStrategy extends JoinStrategy {

  def roleWeights(implicit cfg: GlobalConfig) = cfg.asConfig("node.cluster.join.role-weights")

  def roleWeight(role: String)(implicit cfg: GlobalConfig) = roleWeights.asInt(role, 0)

  def sumOfRoleWeights(roles: Set[String])(implicit cfg: GlobalConfig) = roles.map(roleWeight).sum

  override protected def pickFrom(a: ReachableCluster, b: ReachableCluster)(implicit cfg: GlobalConfig): ReachableCluster =
    (sumOfRoleWeights(a.roles), sumOfRoleWeights(b.roles)) match {
      case (aW, bW) if aW > bW => a
      case (aW, bW) if aW < bW => b
      case _ if a.members.size > b.members.size => a
      case _ if a.members.size < b.members.size => b
      case _ if a.age.nonEmpty && b.age.nonEmpty && a.age.get > b.age.get => a
      case _ if a.age.nonEmpty && b.age.nonEmpty && a.age.get < b.age.get => b
      case _ => a
    }

}
