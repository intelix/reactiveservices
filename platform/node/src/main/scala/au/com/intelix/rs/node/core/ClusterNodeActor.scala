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
package au.com.intelix.rs.node.core

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.evt.{CommonEvt, InfoE}
import au.com.intelix.rs.core.actors._
import au.com.intelix.rs.core.services.BaseServiceActor.StopRequest
import au.com.intelix.rs.node.core.ClusterNodeActor._
import au.com.intelix.rs.node.core.discovery.DiscoveryMessages.ReachableClusters
import au.com.intelix.rs.node.core.discovery.regionbased.ClusterRegionsMonitorActor
import au.com.intelix.rs.node.core.discovery.{ClusterWatcherActor, JoinStrategy, RolePriorityStrategy}

import scala.collection.JavaConversions
import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.Scalaz._

object ClusterNodeActor {

  object Evt {
    case object ClustersDiscovered extends InfoE
    case object JoiningCluster extends InfoE
    case object JoinedCluster extends InfoE
    case object RemovedFromCluster extends InfoE
    case object UnableToJoinCluster extends InfoE
    case object ClusterMergeTrigger extends InfoE
    case object StartingService extends InfoE
    case object StoppingService extends InfoE
  }

  case class ServiceNodeData(joinStrategy: JoinStrategy, seedsToJoin: Set[Address] = Set.empty, reachableClusters: Option[ReachableClusters] = None)

  val DiscoveryMgrId = "discovery-mgr"


  private case class ServiceMeta(id: String, cl: String)

  private object States {
    case object Initial extends ActorState
    case object ClusterDiscovery extends ActorState
    case object ClusterFormationPending extends ActorState
    case object Joining extends ActorState
    case object Joined extends ActorState
  }

  private object InternalMessages {
    case object Start
    case object JoinTimeout
    case object DiscoveryTimeout
    case object CheckState
    case class MemberGone(addr: Address)
  }

}


class ClusterNodeActor extends StatefulActor[Any] {

  import InternalMessages._
  import States._


  implicit val sys = context.system

  implicit val cluster = Cluster(context.system)

  private lazy val selfAddress = cluster.selfAddress


  private val seedRoles = nodeCfg.asStringList("node.cluster.discovery.seed-roles")
  private lazy val mySeed = if (seedRoles.exists(cluster.selfRoles.contains)) Some(cluster.selfAddress) else None


  private val discoveryManager = context.actorOf(Props(nodeCfg.asClass("node.cluster.discovery.provider", classOf[ClusterRegionsMonitorActor])), DiscoveryMgrId)
  private val joinTimeout = nodeCfg.asFiniteDuration("node.cluster.join-timeout", 20 seconds)
  private val discoveryTimeout = nodeCfg.asFiniteDuration("node.cluster.discovery.timeout", 10 seconds)


  private var runningServices: Set[ActorRef] = Set.empty
  private lazy val services: List[ServiceMeta] = JavaConversions.asScalaSet(nodeCfg.asConfig("node.services").entrySet()).map {
    case e => ServiceMeta(e.getKey, e.getValue.unwrapped().toString)
  }.toList

  private val maxRetries = nodeCfg.asInt("node.cluster.service-max-retries", -1)
  private val maxRetriesTimewindow: Duration = nodeCfg.asFiniteDuration("node.cluster.service-max-retries-window", 1 days)
  private val startServicesBeforeCluster = nodeCfg.asBoolean("node.start-services-before-cluster", defaultValue = false)

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = maxRetries, withinTimeRange = maxRetriesTimewindow, loggingEnabled = false) {
      case x: Exception =>
        raise(CommonEvt.Evt.SupervisorRestartTrigger, 'Message -> x.getMessage, 'Cause -> x)
        x.printStackTrace()
        Restart
      case x =>
        Escalate
    }


  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[LeaderChanged], classOf[MemberRemoved], classOf[MemberLeft], classOf[MemberExited])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }

  context.actorOf(Props[ClusterWatcherActor], "watcher") // TODO ok to do it here or should I move it to one of the lifecycle hooks?

  startWith(Initial, ServiceNodeData(
    joinStrategy = nodeCfg.asClass[JoinStrategy]("node.cluster.join.strategy", classOf[RolePriorityStrategy]).newInstance()
  ))

  self ! Start

  when(Initial) {
    case Event(Start, _) =>
      if (startServicesBeforeCluster) startProviders()
      transitionTo(ClusterDiscovery)
  }

  when(ClusterDiscovery) {
    case Event(DiscoveryTimeout, state: ServiceNodeData) => transitionTo(ClusterFormationPending)
    case Event(CheckState, state: ServiceNodeData) => joinExistingCluster(state) | stay()
  }

  when(ClusterFormationPending) {
    case Event(CheckState, state: ServiceNodeData) =>
      joinExistingCluster(state) getOrElse ifSeedFormCluster(state) | stay()
  }

  when(Joining) {
    case Event(JoinTimeout, state: ServiceNodeData) =>
      raise(Evt.UnableToJoinCluster, 'seeds -> state.seedsToJoin)
      stop(FSM.Failure("Unable to join cluster, seeds: " + state.seedsToJoin))
    case Event(LeaderChanged(Some(a)), _) => transitionTo(Joined)
    case Event(CheckState, _) => stay()
  }

  when(Joined) {
    case Event(CheckState, state: ServiceNodeData) if state.reachableClusters.exists(_.our.exists(_.members.nonEmpty)) =>
      mergeWithExistingCluster(state) | stay()
    case Event(LeaderChanged(_), _) => stay()
    case Event(CheckState, _) => stay()
  }

  onTransition {
    case _ -> ClusterDiscovery =>
      setTimer("timeout", DiscoveryTimeout, discoveryTimeout, repeat = false)
    case _ -> Joining =>
      val state = nextStateData.asInstanceOf[ServiceNodeData]
      cancelTimer("checkstate")
      setTimer("timeout", JoinTimeout, joinTimeout, repeat = false)
      raise(Evt.JoiningCluster, 'seeds -> state.seedsToJoin)
      cluster.joinSeedNodes(state.seedsToJoin.toList)
    case _ -> ClusterFormationPending =>
      cancelTimer("timeout")
      setTimer("checkstate", CheckState, 300 millis, repeat = true)
    case _ -> Joined =>
      cancelTimer("timeout")
      cancelTimer("checkstate")
      raise(Evt.JoinedCluster)
      if (!startServicesBeforeCluster) startProviders()
  }

  otherwise {
    case Event(s@ReachableClusters(our, other), state: ServiceNodeData) =>
      raise(Evt.ClustersDiscovered, 'our -> our.map(_.toString), 'other -> other.map(_.toString).mkString(","))
      self ! CheckState
      stay using state.copy(reachableClusters = Some(s))


    case Event(Terminated(ref), _) if runningServices.contains(ref) =>
      stop(FSM.Failure("Service terminated " + ref))

    case Event(StopRequest, _) if runningServices.contains(sender()) =>
      val actor = sender()
      context.unwatch(actor)
      raise(Evt.StoppingService, 'ref -> actor)
      runningServices -= actor
      context.stop(actor)
      stay()

    case Event(MemberExited(m), _) =>
      self ! InternalMessages.MemberGone(m.address)
      stay()
    case Event(MemberLeft(m), _) =>
      self ! InternalMessages.MemberGone(m.address)
      stay()
    case Event(MemberRemoved(m, _), _) =>
      self ! InternalMessages.MemberGone(m.address)
      stay()

    case Event(InternalMessages.MemberGone(a), _) if a == selfAddress =>
      raise(Evt.RemovedFromCluster)
      stop()
    case Event(InternalMessages.MemberGone(a), _) => stay()

  }


  private def joinExistingCluster(state: ServiceNodeData) = state.reachableClusters.flatMap { reachable =>
    state.joinStrategy.selectClusterToJoin(reachable.our, reachable.other.filterNot(_.members.contains(selfAddress))) map { c =>
      transitionTo(Joining) using state.copy(seedsToJoin = c.members.filter(_ != selfAddress), reachableClusters = None)
    }
  }

  private def ifSeedFormCluster(state: ServiceNodeData) = mySeed.map { addr =>
    transitionTo(Joining) using state.copy(seedsToJoin = Set(addr))
  }


  private def mergeWithExistingCluster(state: ServiceNodeData) = state.reachableClusters.map { reachable =>
    state.joinStrategy.selectClusterToJoin(reachable.our, reachable.other) match {
      case None => stay()
      case Some(c) =>
        raise(Evt.ClusterMergeTrigger, 'other -> c)
        stop()
    }
  }


  private def startProvider(sm: ServiceMeta) = {
    val actor = context.watch(context.actorOf(Props(Class.forName(sm.cl)), sm.id))
    raise(Evt.StartingService, 'service -> sm.id, 'class -> sm.cl, 'ref -> actor)
    runningServices += actor
  }

  private def startProviders() = services foreach startProvider

}
