/*
 * Copyright 2014-16 Intelix Pty Ltd
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

trait ClusterAwareness extends BaseActor with ClusterMembershipEventSubscription {

  var reachableMembers: Map[Address, Member] = Map.empty
  var allMembers: Map[Address, Member] = Map.empty

  var leader: Option[Address] = None

  def isClusterLeader = leader.contains(cluster.selfAddress)

  def isAddressReachable(address: Address) = reachableMembers contains address

  def membersWithRole(role: String) = reachableMembers.collect {
    case (a, m) if m.roles.contains(role) => a
  }

  private var clusterMemberUpChain = List[PartialFunction[(Address, Set[String]), Unit]]()
  private var clusterMemberUnreachableChain = List[PartialFunction[(Address, Set[String]), Unit]]()
  private var clusterMemberRemovedChain = List[PartialFunction[(Address, Set[String]), Unit]]()
  private var clusterRolesChangedChain = List[PartialFunction[Set[String], Unit]]()
  private var clusterRolesAddedChain = List[PartialFunction[Set[String], Unit]]()
  private var clusterRolesLostChain = List[PartialFunction[Set[String], Unit]]()
  private var clusterLeaderHandoverChain = List[() => Unit]()
  private var clusterLeaderTakeoverChain = List[() => Unit]()
  private var clusterLeaderChangedChain = List[PartialFunction[Option[Address], Unit]]()
  private var clusterReachableMemberSetChangedChain = List[PartialFunction[Map[Address, Member], Unit]]()
  private var clusterAnyMemberSetChangedChain = List[PartialFunction[Map[Address, Member], Unit]]()

  final def clusterRoles = allMembers.values.flatMap(_.roles).toSet

  final def onClusterMemberUp(f: PartialFunction[(Address, Set[String]), Unit]): Unit = clusterMemberUpChain :+= f

  final def onClusterMemberUnreachable(f: PartialFunction[(Address, Set[String]), Unit]): Unit = clusterMemberUnreachableChain :+= f

  final def onClusterMemberRemoved(f: PartialFunction[(Address, Set[String]), Unit]): Unit = clusterMemberRemovedChain :+= f

  final def onClusterReachableMemberSetChanged(f: PartialFunction[Map[Address, Member], Unit]): Unit = clusterReachableMemberSetChangedChain :+= f

  final def onClusterAnyMemberSetChanged(f: PartialFunction[Map[Address, Member], Unit]): Unit = clusterAnyMemberSetChangedChain :+= f

  final def onClusterRolesChanged(f: PartialFunction[Set[String], Unit]): Unit = clusterRolesChangedChain :+= f

  final def onClusterRolesLost(f: PartialFunction[Set[String], Unit]): Unit = clusterRolesLostChain :+= f

  final def onClusterRolesAdded(f: PartialFunction[Set[String], Unit]): Unit = clusterRolesAddedChain :+= f

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
    case MemberUp(member) =>
      addReachable(member, clusterMemberUpChain)
      add(member)
    case ReachableMember(member) =>
      addReachable(member, clusterMemberUpChain)
      add(member)
    case MemberRemoved(member, previousStatus) =>
      removeReachable(member, clusterMemberRemovedChain)
      remove(member)
    case MemberLeft(member) =>
      removeReachable(member, clusterMemberRemovedChain)
      remove(member)
    case MemberExited(member) =>
      removeReachable(member, clusterMemberRemovedChain)
      remove(member)
    case UnreachableMember(member) =>
      removeReachable(member, clusterMemberUnreachableChain)
      add(member)
    case MemberJoined(member) =>
      add(member)
    case MemberWeaklyUp(member) =>
      add(member)
    case LeaderChanged(l) => processLeaderChange(l)
  }

  private def removeReachable(member: Member, notify: List[PartialFunction[(Address, Set[String]), Unit]]) = {
    reachableMembers = reachableMembers - member.address
    notify.foreach(_.applyOrElse((member.address, member.roles), (_: Any) => ()))
    notifyClusterReachableMemberSetChange()
  }

  private def addReachable(member: Member, notify: List[PartialFunction[(Address, Set[String]), Unit]]) = {
    reachableMembers = reachableMembers + (member.address -> member)
    notify.foreach(_.applyOrElse((member.address, member.roles), (_: Any) => ()))
    notifyClusterReachableMemberSetChange()
  }

  private def remove(member: Member) =
    if (allMembers contains member.address) doWithRolesTracking {
      allMembers = allMembers - member.address
      notifyClusterMemberSetChange()
    }

  private def add(member: Member) =
    if (!(allMembers contains member.address)) doWithRolesTracking {
      allMembers = allMembers + (member.address -> member)
      notifyClusterMemberSetChange()
    }

  private def notifyClusterReachableMemberSetChange() = clusterReachableMemberSetChangedChain.foreach(_.applyOrElse(reachableMembers, (_: Any) => ()))

  private def notifyClusterMemberSetChange() = clusterAnyMemberSetChangedChain.foreach(_.applyOrElse(allMembers, (_: Any) => ()))


  private def doWithRolesTracking(operation: => Unit): Unit = {
    val rolesBeforeOp = clusterRoles
    operation
    val rolesAfterOp = clusterRoles
    if (rolesBeforeOp != rolesAfterOp) {
      clusterRolesChangedChain.foreach(_.applyOrElse(rolesAfterOp, (_: Any) => ()))
      val added = rolesAfterOp -- rolesBeforeOp
      val lost = rolesBeforeOp -- rolesAfterOp
      if (added.nonEmpty) clusterRolesAddedChain.foreach(_.applyOrElse(added, (_: Any) => ()))
      if (lost.nonEmpty) clusterRolesLostChain.foreach(_.applyOrElse(lost, (_: Any) => ()))
    }

  }


}
