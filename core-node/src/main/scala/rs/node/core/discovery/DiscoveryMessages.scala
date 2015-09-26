package rs.node.core.discovery

import akka.actor.Address

object DiscoveryMessages {

  case class ReachableClusters(our: Option[ReachableCluster], other: Set[ReachableCluster])
  case class ReachableNodes(list: Set[Address])

}
