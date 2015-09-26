package rs.node.core.discovery

import rs.core.config.GlobalConfig


trait JoinStrategy {
  def selectClusterToJoin(our: Option[ReachableCluster], other: Set[ReachableCluster])(implicit cfg: GlobalConfig): Option[ReachableCluster] =
    other.foldLeft[Option[ReachableCluster]](our) {
        case (None, next) => Some(next)
        case (Some(pick), next) => Some(pickFrom(pick, next))
      } match {
      case Some(pick) if isOurCluster(our, pick) => None
      case x => x
    }

  protected def isOurCluster(our: Option[ReachableCluster], other: ReachableCluster) = other.members.exists { m => our.exists(_.members.contains(m)) }

  protected def pickFrom(a: ReachableCluster, b: ReachableCluster)(implicit cfg: GlobalConfig): ReachableCluster
}
