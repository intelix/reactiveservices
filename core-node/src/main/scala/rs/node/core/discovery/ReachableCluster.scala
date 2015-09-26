package rs.node.core.discovery

import akka.actor.Address

case class ReachableCluster(members: Set[Address], roles: Set[String], age: Option[Long]) {
  override lazy val toString: String = "{members=" + members + ";roles=" + roles.toList.mkString(",") + "}"
}
