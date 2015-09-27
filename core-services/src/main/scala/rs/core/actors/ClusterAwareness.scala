/*
 * Copyright 2014-15 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rs.core.actors

import akka.actor.Address
import akka.cluster.ClusterEvent._
import akka.cluster.Member

trait ClusterAwareness extends ClusterMembershipEventSubscription {

  _: FSMActor =>

  var reachableMembers: Map[Address, Member] = Map.empty

  var leader: Option[Address] = None

  def isClusterLeader = leader.contains(cluster.selfAddress)

  def isAddressReachable(address: Address) = reachableMembers contains address

  def membersWithRole(role: String) = reachableMembers.collect {
    case (a, m) if m.roles.contains(role) => a
  }

//  private var clusterStateSnapshotChain = List[() => Unit]()
  private var clusterMemberUpChain = List[PartialFunction[(Address, Set[String]), Unit]]()
  private var clusterMemberUnreachableChain = List[PartialFunction[(Address, Set[String]), Unit]]()
  private var clusterMemberRemovedChain = List[PartialFunction[(Address, Set[String]), Unit]]()
  private var clusterLeaderHandoverChain = List[() => Unit]()
  private var clusterLeaderTakeoverChain = List[() => Unit]()
  private var clusterLeaderChangedChain = List[PartialFunction[Option[Address], Unit]]()

//  final def onClusterStateSnapshot(f: => Unit): Unit = clusterStateSnapshotChain :+= (() => f)

  final def onClusterMemberUp(f: PartialFunction[(Address, Set[String]), Unit]): Unit = clusterMemberUpChain :+= f

  final def onClusterMemberUnreachable(f: PartialFunction[(Address, Set[String]), Unit]): Unit = clusterMemberUnreachableChain :+= f

  final def onClusterMemberRemoved(f: PartialFunction[(Address, Set[String]), Unit]): Unit = clusterMemberRemovedChain :+= f

  final def onLeaderHandover(f: => Unit): Unit = clusterLeaderHandoverChain :+= (() => f)

  final def onLeaderTakeover(f: => Unit): Unit = clusterLeaderTakeoverChain :+= (() => f)

  final def onLeaderChanged(f: PartialFunction[Option[Address], Unit]): Unit = clusterLeaderChangedChain :+= f

  private def processLeaderChange(l: Option[Address]) =
    if (l != leader) {
      val wasLeader = isClusterLeader
      leader = l
      if (wasLeader && !isClusterLeader) clusterLeaderHandoverChain.foreach(_.apply())
      if (!wasLeader && isClusterLeader) clusterLeaderTakeoverChain.foreach(_.apply())
      clusterLeaderChangedChain.foreach(_.applyOrElse(l, (_: Any) => ()))
    }


  onMessage {
//    case CurrentClusterState(m, u, _, l, _) =>
//      m.foreach { member => clusterMemberUpChain.foreach(_.applyOrElse((member.address, member.roles), (_: Any) => ())) }
//      processLeaderChange(l)
//      clusterStateSnapshotChain.foreach(_.apply())
    case MemberUp(member) =>
      reachableMembers = reachableMembers + (member.address -> member)
      clusterMemberUpChain.foreach(_.applyOrElse((member.address, member.roles), (_: Any) => ()))
    case UnreachableMember(member) =>
      reachableMembers = reachableMembers - member.address
      clusterMemberUnreachableChain.foreach(_.applyOrElse((member.address, member.roles), (_: Any) => ()))
    case ReachableMember(member) =>
      reachableMembers = reachableMembers + (member.address -> member)
      clusterMemberUpChain.foreach(_.applyOrElse((member.address, member.roles), (_: Any) => ()))
    case MemberRemoved(member, previousStatus) =>
      reachableMembers = reachableMembers - member.address
      clusterMemberRemovedChain.foreach(_.applyOrElse((member.address, member.roles), (_: Any) => ()))
    case MemberExited(member) =>
      reachableMembers = reachableMembers - member.address
      clusterMemberRemovedChain.foreach(_.applyOrElse((member.address, member.roles), (_: Any) => ()))
    case LeaderChanged(l) => processLeaderChange(l)
  }

}
