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
package rs.node.core

import akka.actor.{ActorRef, Address, FSM, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import net.ceedubs.ficus.Ficus._
import rs.core.actors.{ActorUtils, BaseActorSysevents}
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.core.tools.UUIDTools
import rs.core.tools.metrics.WithCHMetrics
import rs.node.core.ServiceNodeActor._

import scala.collection.JavaConversions
import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.Scalaz._

trait ServiceNodeSysevents extends ComponentWithBaseSysevents with BaseActorSysevents {

  val StartingService = "StartingService".trace
  val DiscoveryTimeout = "DiscoveryTimeout".info
  val StateChange = "StateChange".info
  val UnableToJoinCluster = "UnableToJoinCluster".error
  val ClusterMergeTrigger = "ClusterMergeTrigger".info
  val FormingClusterWithSelf = "FormingClusterWithSelf".info
  val JoiningCluster = "JoiningCluster".info

  override def componentId: String = "Cluster.Node"
}

object ServiceNodeActor {

  sealed trait ClusterState

  case object ClusterStateNotJoined extends ClusterState

  case object ClusterStateUnstable extends ClusterState

  case object ClusterStateStable extends ClusterState


  sealed trait MemberState

  case object MemberStateUp extends MemberState

  case object MemberStateUnavailable extends MemberState

  case object PerformHandshake

  case object CheckState

  case object Start

  case object PerformJoinIfPossible

  case class ClusterView(correlationId: String, formed: Boolean, activeMembers: Set[Address])

  case class ClusterViewEnquiry(correlationId: String)

  case class NodePerspective(addr: String, clusterView: Option[ClusterView])

  case class ServiceMeta(id: String, cl: String)

  private case object DiscoveryTimeout

  private case object JoinTimeout

  sealed trait State

  case object Initial extends State

  case object DetachedWaitingForAllSeeds extends State

  case object DetachedWaitingForFirstSeed extends State

  case object AwaitingJoin extends State

  case object PartiallyBuilt extends State

  case object FullyBuilt extends State

  case class Data(selfAddress: String,
                  clusterNodes: List[String],
                  clusterGuardian: Option[ActorRef] = None,
                  perspectives: Map[String, NodePerspective] = Map.empty,
                  members: Map[Address, MemberState] = Map.empty,
                  leader: Option[Address] = None,
                  seeds: List[Address] = List.empty) {

    def isReceivedResponsesFromAllNodes = perspectives.nonEmpty && perspectives.values.forall(_.clusterView.isDefined)

    def isFormedClusterFound = perspectives.values.exists(_.clusterView.exists(_.formed))

    def isHigherPriorityNodeAlive =
      clusterNodes.takeWhile(_ != selfAddress).exists { node => perspectives.values.find(_.addr.equalsIgnoreCase(node)).exists(_.clusterView.isDefined) }

    def canJoinSelf = clusterNodes.contains(selfAddress)

    def locateMembersOfAnotherLargestFormedCluster: Option[Set[Address]] =
      perspectives.foldLeft[Option[Set[Address]]](None) {
        case (pick, (a, NodePerspective(addr, Some(cv)))) if cv.formed && !isBelongToMyCluster(cv.activeMembers) =>
          pick match {
            case Some(set) if set.size >= cv.activeMembers.size => pick
            case _ => Some(cv.activeMembers)
          }
        case (pick, _) => pick
      }

    def isBelongToMyCluster(activeMembers: Set[Address]) = activeMembers.exists(members.contains)

    //activeMembers.exists { next => isMyMember(next.toString) }

    def hasMembers = members.nonEmpty

    def hasLeader = leader.isDefined

    def hasUnreachableMembers = members.exists(_._2 == MemberStateUnavailable)

    def currentClusterState =
      if (!hasMembers) ClusterStateNotJoined
      else if (!hasLeader || hasUnreachableMembers) ClusterStateUnstable
      else ClusterStateStable

    def isExpectedMemberMissing = clusterNodes.exists { n => !members.exists(_._1.toString.equalsIgnoreCase(n)) }

    def isAllExpectedMembersPresent = !isExpectedMemberMissing

    def shouldMergeWith(candidateMembers: Set[Address]): Boolean =
      candidateMembers.size > members.size || haveHigherPriorityMember(candidateMembers)

    def isMyMember(node: String) = selfAddress.equalsIgnoreCase(node) || members.exists(_._1.toString.equalsIgnoreCase(node))

    def isNotMyMember(node: String) = !isMyMember(node)

    def haveHigherPriorityMember(candidateMembers: Set[Address]): Boolean =
      clusterNodes takeWhile isNotMyMember exists { node => candidateMembers.exists(_.toString.equalsIgnoreCase(node)) }

    def missingClusterNodes = clusterNodes.filter(isNotMyMember)

    def addResponse(response: ClusterView) =
      this.copy(perspectives =
        perspectives map {
          case (k, v) if k == response.correlationId => k -> v.copy(clusterView = Some(response))
          case (k, v) => k -> v
        })

  }

}

class ServiceNodeActor
  extends FSM[State, Data]
  with WithSyseventPublisher
  with ActorUtils
  with ServiceNodeSysevents
  with WithCHMetrics {

  implicit val cluster = Cluster(context.system)
  private val selfAddress = cluster.selfAddress

  lazy val clusterSystemId = config.as[Option[String]]("node.cluster.system-id") | "cluster"

  lazy val clusterNodes = config.as[Option[List[String]]]("node.cluster.core-nodes") match {
    case None => List(selfAddress.toString)
    case Some(l) if l.isEmpty => List(selfAddress.toString)
    case Some(l) => l.map(s"akka.tcp://$clusterSystemId@" + _)
  }

  lazy val services: List[ServiceMeta] = JavaConversions.asScalaSet(config.getConfig("node.services").entrySet()).map {
    case e => ServiceMeta(e.getKey, e.getValue.unwrapped().toString)
  }.toList


  private def controllerSelectorAt(addr: String) = context.actorSelection(addr + context.self.path.toStringWithoutAddress)

  private def contactNode(node: String) = {
    val correlation = UUIDTools.generateShortUUID
    controllerSelectorAt(node) ! ClusterViewEnquiry(correlation)
    correlation -> NodePerspective(node, None)
  }


  private def isHigherPriority(addr: Address, otherAddr: Address): Boolean =
    (clusterNodes.indexOf(addr.toString), clusterNodes.indexOf(otherAddr.toString)) match {
      case (_, -1) => false
      case (-1, _) => true
      case (a, b) => a > b
    }

  var servicesCounter = 0

  private def startProvider(sm: ServiceMeta) = StartingService { ctx =>
    servicesCounter += 1
    val id = sm.id + "-" + servicesCounter
    val actor = context.actorOf(Props(Class.forName(sm.cl), sm.id), id)
    ctx +('service -> sm.id, 'class -> sm.cl, 'ref -> actor)
  }

  private def startProviders() = {
    services foreach startProvider
  }


  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[LeaderChanged],
      classOf[MemberUp],
      classOf[UnreachableMember],
      classOf[ReachableMember],
      classOf[MemberRemoved],
      classOf[MemberExited]
    )
  }


  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }

  private def transitionTo(state: ServiceNodeActor.State) = {
    if (stateName != state) StateChange('to -> state, 'from -> stateName)
    goto(state)
  }

  startWith(Initial, Data(selfAddress = selfAddress.toString, clusterNodes = clusterNodes))

  when(Initial) {
    case Event(Start, t) => transitionTo(DetachedWaitingForAllSeeds) using t.copy(clusterGuardian = Some(sender()))
  }

  when(DetachedWaitingForAllSeeds) {
    case Event(DiscoveryTimeout, _) =>
      DiscoveryTimeout()
      transitionTo(DetachedWaitingForFirstSeed)
    case Event(CheckState, t: Data) =>
      if (t.isReceivedResponsesFromAllNodes) self ! PerformJoinIfPossible
      stay()
  }

  when(DetachedWaitingForFirstSeed) {
    case Event(CheckState, t: Data) =>
      self ! PerformJoinIfPossible
      stay()
  }

  when(AwaitingJoin) {
    case Event(JoinTimeout, t) =>
      UnableToJoinCluster('seeds -> t.seeds)
      stop(FSM.Failure("Unable to join cluster, seeds: " + t.seeds))
    case Event(PerformJoinIfPossible, t: Data) => stay()
    case Event(CheckState, t: Data) => t.currentClusterState match {
      case ClusterStateStable | ClusterStateUnstable =>
        if (t.isExpectedMemberMissing) transitionTo(PartiallyBuilt) else transitionTo(FullyBuilt)
      case _ => stay()
    }
  }

  when(PartiallyBuilt) {
    case Event(CheckState, t) =>
      if (t.isAllExpectedMembersPresent) transitionTo(FullyBuilt)
      else if (t.isFormedClusterFound && t.locateMembersOfAnotherLargestFormedCluster.exists(t.shouldMergeWith)) {
        ClusterMergeTrigger('other -> t.locateMembersOfAnotherLargestFormedCluster)
        stop()
      } else stay()
    case Event(e: ClusterViewEnquiry, t: Data) =>
      sender() ! ClusterView(e.correlationId, formed = true, t.members.keySet)
      stay()
  }

  when(FullyBuilt) {
    case Event(CheckState, t) => if (t.isExpectedMemberMissing) transitionTo(PartiallyBuilt) else stay()
    case Event(e: ClusterViewEnquiry, t: Data) =>
      sender() ! ClusterView(e.correlationId, formed = true, t.members.keySet)
      stay()
  }

  whenUnhandled {
    case Event(PerformHandshake, t: Data) =>
      stay using t.copy(perspectives = t.missingClusterNodes.map(contactNode).toMap)
    case Event(e: ClusterView, t: Data) =>
      self ! CheckState
      transitionTo(stateName) using t.addResponse(e)
    case Event(e: ClusterViewEnquiry, t: Data) =>
      sender() ! ClusterView(e.correlationId, formed = false, Set.empty)
      stay()
    case Event(PerformJoinIfPossible, t: Data) =>
      if (t.isFormedClusterFound) {
        t.locateMembersOfAnotherLargestFormedCluster match {
          case Some(members) => transitionTo(AwaitingJoin) using t.copy(seeds = members.toList.filter(_ != selfAddress).sortWith(isHigherPriority), perspectives = Map.empty)
          case None => stay()
        }
      } else if (!t.isHigherPriorityNodeAlive && t.canJoinSelf) {
        FormingClusterWithSelf('self -> selfAddress)
        transitionTo(AwaitingJoin) using t.copy(seeds = List(selfAddress), perspectives = Map.empty)
      } else stay()
    case Event(LeaderChanged(leader), t) =>
      self ! CheckState
      stay using t.copy(leader = leader)
    case Event(MemberExited(member), t) =>
      self ! CheckState
      stay using t.copy(members = t.members - member.address)
    case Event(MemberRemoved(member, _), t) =>
      self ! CheckState
      stay using t.copy(members = t.members - member.address)
    case Event(UnreachableMember(member), t) =>
      self ! CheckState
      stay using t.copy(members = t.members + (member.address -> MemberStateUnavailable))
    case Event(ReachableMember(member), t) =>
      self ! CheckState
      stay using t.copy(members = t.members + (member.address -> MemberStateUp))
    case Event(MemberUp(member), t) =>
      self ! CheckState
      stay using t.copy(members = t.members + (member.address -> MemberStateUp))
    case Event(DiscoveryTimeout, _) => stay()
    case Event(JoinTimeout, _) => stay()
    case Event(x, _) => Invalid('msg -> x)
      stay()
  }

  onTransition {
    case _ -> DetachedWaitingForAllSeeds =>
      setTimer("timeout", DiscoveryTimeout, 10 seconds, repeat = false)
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 3 seconds, repeat = true)
    case _ -> DetachedWaitingForFirstSeed =>
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 3 seconds, repeat = true)
    case _ -> AwaitingJoin =>
      setTimer("timeout", JoinTimeout, 20 seconds, repeat = false)
      cancelTimer("handshake")
      setTimer("checkState", CheckState, 1 seconds, repeat = true)
      JoiningCluster('seeds -> nextStateData.seeds)
      cluster.joinSeedNodes(nextStateData.seeds)
    case AwaitingJoin -> PartiallyBuilt =>
      cancelTimer("timeout")
      startProviders()
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 3 seconds, repeat = true)
    case _ -> PartiallyBuilt =>
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 3 seconds, repeat = true)
    case AwaitingJoin -> FullyBuilt =>
      cancelTimer("timeout")
      startProviders()
      cancelTimer("handshake")
      cancelTimer("checkState")
    case _ -> FullyBuilt =>
      cancelTimer("handshake")
      cancelTimer("checkState")
  }

  initialize()

}

