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

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.remote.QuarantinedEvent
import rs.core.actors.{ActorUtils, BaseActorSysevents, WithGlobalConfig}
import rs.core.config.ConfigOps.wrap
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.core.tools.metrics.WithCHMetrics
import rs.node.core.ServiceNodeActor._

import scala.collection.JavaConversions
import scala.concurrent.duration._
import scala.language.postfixOps

trait ServiceNodeSysevents extends ComponentWithBaseSysevents with BaseActorSysevents {

  val StartingService = "StartingService".trace
  val DiscoveryTimeout = "DiscoveryTimeout".info
  val StateChange = "StateChange".info
  val UnableToJoinCluster = "UnableToJoinCluster".error
  val ClusterMergeTrigger = "ClusterMergeTrigger".warn
  val QuarantineRecoveryTrigger = "QuarantineRecoveryTrigger".warn
  val FormingClusterWithSelf = "FormingClusterWithSelf".info
  val JoiningCluster = "JoiningCluster".info
  val NewLeaderElected = "NewLeaderElected".info
  val ClusterViewSent = "ClusterViewSent".trace
  val ClusterViewRequested = "ClusterViewRequested".trace

  val NodeUp = "NodeUp".info
  val NodeRemoved = "NodeRemoved".info
  val NodeExited = "NodeExited".info
  val NodeUnreachable = "NodeUnreachable".info
  val NodeReachable = "NodeReachable".info
  val NodeQuarantined = "NodeQuarantined".warn

  override def componentId: String = "Cluster.Node"
}

object ServiceNodeSysevents extends ServiceNodeSysevents

object ServiceNodeActor {

  sealed trait ClusterState

  case object ClusterStateNotJoined extends ClusterState

  case object ClusterStateUnstable extends ClusterState

  case object ClusterStateStable extends ClusterState


  sealed trait MemberState

  case object MemberStateUp extends MemberState

  case object MemberStateUnavailable extends MemberState

  case object MemberStateQuarantined extends MemberState

  case object PerformHandshake

  case object CheckState

  case object CheckQuarantinedState

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

  case object Discovery extends State

  case object NodeSelection extends State

  case object AwaitingJoin extends State

  case object PartiallyBuilt extends State

  case object FullyBuilt extends State

  case class Data(selfAddress: String,
                  configuredClusterNodes: List[String],
                  clusterGuardian: Option[ActorRef] = None,
                  perspectives: Map[String, ClusterView] = Map.empty,
                  currentMembers: Map[Address, MemberState] = Map.empty,
                  currentLeader: Option[Address] = None,
                  seedsToJoin: List[Address] = List.empty) {

    def isReceivedResponsesFromAllNodes = missingClusterNodesWithoutPerspectives.isEmpty

    def missingClusterNodesWithoutPerspectives = missingClusterNodes.filterNot(perspectives.contains)

    def missingClusterNodesWithPerspectives = missingClusterNodes.filter(perspectives.contains)

    def isFormedClusterFound = missingClusterNodesWithPerspectives.exists(perspectives.get(_).exists(_.formed))

    def isHigherPriorityNodeAlive = configuredClusterNodes.takeWhile(_ != selfAddress).exists(perspectives.contains)

    def isHigherPriorityNodeQuarantined = configuredClusterNodes.takeWhile(_ != selfAddress).exists(isMemberQuarantined)

    def canJoinSelf = configuredClusterNodes.contains(selfAddress)

    def locateMembersOfAnotherLargestFormedCluster: Option[Set[Address]] =
      perspectives.filterKeys(missingClusterNodes.contains).foldLeft[Option[Set[Address]]](None) {
        case (pick, (a, cv)) if cv.formed && !isBelongToMyCluster(cv.activeMembers) =>
          pick match {
            case Some(set) if set.size >= cv.activeMembers.size => pick
            case _ => Some(cv.activeMembers)
          }
        case (pick, _) => pick
      }

    def isBelongToMyCluster(membersToCheck: Set[Address]) = membersToCheck.exists(currentMembers.contains)

    def hasMembers = currentMembers.nonEmpty

    def hasLeader = currentLeader.isDefined

    def hasUnreachableMembers = currentMembers.exists(_._2 != MemberStateUp)

    def currentClusterState =
      if (!hasMembers) ClusterStateNotJoined
      else if (!hasLeader || hasUnreachableMembers) ClusterStateUnstable
      else ClusterStateStable

    def isExpectedMemberMissing = configuredClusterNodes.exists { n => !currentMembers.exists(_._1.toString.equalsIgnoreCase(n)) }

    def isAllExpectedMembersPresent = !isExpectedMemberMissing

    def shouldMergeWith(candidateMembers: Set[Address]): Boolean =
      candidateMembers.size > currentMembers.size || haveHigherPriorityMember(candidateMembers)

    def isMyMember(node: String) = selfAddress.equalsIgnoreCase(node) || currentMembers.exists(_._1.toString.equalsIgnoreCase(node))

    def isMemberQuarantined(node: String) = !selfAddress.equalsIgnoreCase(node) && currentMembers.exists { case (k, v) => k.toString.equalsIgnoreCase(node) && v == MemberStateQuarantined }

    def isNotMyMember(node: String) = !isMyMember(node)

    def haveHigherPriorityMember(candidateMembers: Set[Address]): Boolean =
      configuredClusterNodes takeWhile isNotMyMember exists { node => candidateMembers.exists(_.toString.equalsIgnoreCase(node)) }

    def missingClusterNodes = configuredClusterNodes.filter(isNotMyMember)

    def addResponse(response: ClusterView) = this.copy(perspectives = perspectives + (response.correlationId -> response))


  }

}

class ServiceNodeActor
  extends FSM[State, Data]
  with WithSyseventPublisher
  with ActorUtils
  with ServiceNodeSysevents
  with WithCHMetrics
  with WithGlobalConfig {

  implicit val cluster = Cluster(context.system)
  private val selfAddress = cluster.selfAddress

  lazy val clusterSystemId = config.asString("node.cluster.system-id", "cluster")

  lazy val clusterNodes = config.asStringList("node.cluster.core-nodes") match {
    case l if l.isEmpty => List(selfAddress.toString)
    case l =>
      val proto = config.asString("node.protocol", "akka.tcp")
      l.map(s"$proto://$clusterSystemId@" + _)
  }

  lazy val services: List[ServiceMeta] = JavaConversions.asScalaSet(config.getConfig("node.services").entrySet()).map {
    case e => ServiceMeta(e.getKey, e.getValue.unwrapped().toString)
  }.toList


  private val maxRetries = config.asInt("node.cluster.service-max-retries", -1)
  private val maxRetriesTimewindow: Duration = config.asFiniteDuration("node.cluster.service-max-retries-window", 1 days)
  private val startServicesBeforeCluster = config.asBoolean("node.start-services-before-cluster", defaultValue = false)

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = maxRetries, withinTimeRange = maxRetriesTimewindow) {
      case _: Exception =>
        Restart
      case _ =>
        Escalate
    }

  private def controllerSelectorAt(addr: String) = context.actorSelection(addr + context.self.path.toStringWithoutAddress)

  private def contactNode(node: String) = {
    ClusterViewRequested('target -> node)
    controllerSelectorAt(node) ! ClusterViewEnquiry(node)
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
    //    val id = sm.id + "-" + servicesCounter
    val actor = context.watch(context.actorOf(Props(Class.forName(sm.cl), sm.id), sm.id))
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
    context.system.eventStream.subscribe(self, classOf[QuarantinedEvent])
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

  private def sendLatestClusterView() =
    stateData.missingClusterNodes.foreach { node =>
      val selector = controllerSelectorAt(node)
      ClusterViewSent('target -> selector, 'formed -> true, 'nodes -> Set(selfAddress))
      controllerSelectorAt(node) ! ClusterView(node, formed = true, Set(selfAddress))
    }


  startWith(Initial, Data(selfAddress = selfAddress.toString, configuredClusterNodes = clusterNodes))

  when(Initial) {
    case Event(Start, t) =>

      if (t.configuredClusterNodes.isEmpty)
        throw new Error("No core nodes configured - terminating")

      if (startServicesBeforeCluster) startProviders()

      transitionTo(Discovery) using t.copy(clusterGuardian = Some(sender()))
  }

  when(Discovery) {
    case Event(DiscoveryTimeout, t) =>
      val nodes = t.missingClusterNodesWithoutPerspectives
      DiscoveryTimeout('noResponseFrom -> nodes)
      transitionTo(NodeSelection)
    case Event(CheckState, t: Data) =>
      if (t.isReceivedResponsesFromAllNodes) transitionTo(NodeSelection) else stay()
  }

  when(NodeSelection) {
    case Event(CheckState, t: Data) =>
      self ! PerformJoinIfPossible
      stay()
  }

  when(AwaitingJoin) {
    case Event(JoinTimeout, t) =>
      UnableToJoinCluster('seeds -> t.seedsToJoin)
      stop(FSM.Failure("Unable to join cluster, seeds: " + t.seedsToJoin))
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
      sender() ! ClusterView(e.correlationId, formed = true, t.currentMembers.keySet)
      ClusterViewSent('target -> sender(), 'formed -> true, 'nodes -> t.currentMembers.keySet)
      stay()
  }

  when(FullyBuilt) {
    case Event(CheckState, t) =>
      if (t.isExpectedMemberMissing) transitionTo(PartiallyBuilt) else stay()
    case Event(e: ClusterViewEnquiry, t: Data) =>
      sender() ! ClusterView(e.correlationId, formed = true, t.currentMembers.keySet)
      ClusterViewSent('target -> sender(), 'formed -> true, 'nodes -> t.currentMembers.keySet)
      stay()
  }

  whenUnhandled {
    case Event(PerformHandshake, t: Data) =>
      t.missingClusterNodes.foreach(contactNode)
      stay()
    case Event(e: ClusterView, t: Data) =>
      transitionTo(stateName) using t.addResponse(e)
    case Event(e: ClusterViewEnquiry, t: Data) =>
      sender() ! ClusterView(e.correlationId, formed = false, Set.empty)
      ClusterViewSent('target -> sender(), 'formed -> false, 'nodes -> Set.empty)
      stay()
    case Event(PerformJoinIfPossible, t: Data) =>
      if (t.isFormedClusterFound) {
        t.locateMembersOfAnotherLargestFormedCluster match {
          case Some(members) => transitionTo(AwaitingJoin) using t.copy(seedsToJoin = members.toList.filter(_ != selfAddress).sortWith(isHigherPriority))
          case None => stay()
        }
      } else if (!t.isHigherPriorityNodeAlive && t.canJoinSelf) {
        FormingClusterWithSelf('self -> selfAddress)
        transitionTo(AwaitingJoin) using t.copy(seedsToJoin = List(selfAddress))
      } else stay()
    case Event(LeaderChanged(leader), t) =>
      NewLeaderElected('leader -> leader)
      self ! CheckState
      stay using t.copy(currentLeader = leader)
    case Event(MemberExited(member), t) =>
      NodeExited('addr -> member.address.toString)
      self ! CheckState
      stay using t.copy(currentMembers = t.currentMembers - member.address, perspectives = t.perspectives - member.address.toString)
    case Event(MemberRemoved(member, _), t) =>
      NodeRemoved('addr -> member.address.toString)
      self ! CheckState
      stay using t.copy(currentMembers = t.currentMembers - member.address, perspectives = t.perspectives - member.address.toString)
    case Event(UnreachableMember(member), t) =>
      NodeUnreachable('addr -> member.address.toString)
      self ! CheckState
      stay using t.copy(currentMembers = t.currentMembers + (member.address -> MemberStateUnavailable), perspectives = t.perspectives - member.address.toString)
    case Event(ReachableMember(member), t) =>
      NodeReachable('addr -> member.address.toString)
      self ! CheckState
      stay using t.copy(currentMembers = t.currentMembers + (member.address -> MemberStateUp))
    case Event(MemberUp(member), t) =>
      NodeUp('addr -> member.address.toString)
      self ! CheckState
      stay using t.copy(currentMembers = t.currentMembers + (member.address -> MemberStateUp))
    case Event(DiscoveryTimeout, _) => stay()
    case Event(Terminated(ref), _) => stop(FSM.Failure("Service failure: " + ref))
    case Event(JoinTimeout, _) => stay()
    case Event(QuarantinedEvent(addr, uid), t) =>
      if (clusterNodes.contains(addr.toString)) {
        NodeQuarantined('addr -> addr.toString, 'uid -> uid, 'state -> stateName)
        self ! CheckQuarantinedState
        stay using t.copy(currentMembers = t.currentMembers + (addr -> MemberStateQuarantined))
      } else stay()
    case Event(CheckQuarantinedState, t) =>
      if (t.isHigherPriorityNodeQuarantined) {
        QuarantineRecoveryTrigger('quarantined -> t.currentMembers.filter(_._2 == MemberStateQuarantined).map(_._1.toString))
        stop()
      } else stay()
    case Event(x, _) => Invalid('msg -> x, 'state -> stateName)
      stay()
  }

  onTransition {
    case _ -> Discovery =>
      setTimer("timeout", DiscoveryTimeout, globalCfg.asFiniteDuration("node.cluster.discovery-timeout", 10 seconds), repeat = false)
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 1 seconds, repeat = true)
      self ! CheckState
      self ! PerformHandshake
    case _ -> NodeSelection =>
      setTimer("handshake", PerformHandshake, 2 seconds, repeat = true)
      setTimer("checkState", CheckState, 1 seconds, repeat = true)
      self ! CheckState
    case _ -> AwaitingJoin =>
      setTimer("timeout", JoinTimeout, globalCfg.asFiniteDuration("node.cluster.join-timeout", 20 seconds), repeat = false)
      cancelTimer("handshake")
      setTimer("checkState", CheckState, 1 seconds, repeat = true)
      JoiningCluster('seeds -> nextStateData.seedsToJoin)
      cluster.joinSeedNodes(nextStateData.seedsToJoin)
    case AwaitingJoin -> PartiallyBuilt =>
      sendLatestClusterView()
      cancelTimer("timeout")
      if (!startServicesBeforeCluster) startProviders()
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 1 seconds, repeat = true)
      self ! CheckState
    case _ -> PartiallyBuilt =>
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 3 seconds, repeat = true)
      self ! CheckState
    case AwaitingJoin -> FullyBuilt =>
      sendLatestClusterView()
      if (!startServicesBeforeCluster) startProviders()
      cancelTimer("timeout")
      cancelTimer("handshake")
      cancelTimer("checkState")
    case _ -> FullyBuilt =>
      cancelTimer("handshake")
      cancelTimer("checkState")
  }

  initialize()

}

