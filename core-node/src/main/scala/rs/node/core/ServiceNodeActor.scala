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
import rs.core.actors._
import rs.core.config.ConfigOps.wrap
import rs.core.services.BaseServiceActor.StopRequest
import rs.node.core.ServiceNodeActor._
import rs.node.core.discovery.DiscoveryMessages.{ReachableClusters, ReachableNodes}
import rs.node.core.discovery.{JoinStrategy, RolePriorityStrategy, UdpClusterManagerActor}

import scala.collection.JavaConversions
import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.Scalaz._

trait ServiceNodeActorEvt extends CommonActorEvt {

  val AvailableSeeds = "AvailableSeeds".info
  val ClustersDiscovered = "ClustersDiscovered".info
  val JoiningCluster = "JoiningCluster".info
  val JoinedCluster = "JoinedCluster".info
  val UnableToJoinCluster = "UnableToJoinCluster".error
  val ClusterMergeTrigger = "ClusterMergeTrigger".warn
  val StartingService = "StartingService".trace
  val StoppingService = "StoppingService".trace


  override def componentId: String = "ServiceNode"
}

object ServiceNodeActorEvt extends ServiceNodeActorEvt


object ServiceNodeActor {

  case class ServiceNodeData(joinStrategy: JoinStrategy, availableSeeds: Set[Address] = Set.empty, seedsToJoin: Set[Address] = Set.empty, reachableClusters: Option[ReachableClusters] = None)

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
  }

}


class ServiceNodeActor extends StatefulActor[Any] with ServiceNodeActorEvt {
  import States._
  import InternalMessages._


  implicit val sys = context.system

  implicit val cluster = Cluster(context.system)

  private lazy val selfAddress = cluster.selfAddress


  private val discoveryManager = context.actorOf(Props(nodeCfg.asClass("node.cluster.discovery.provider", classOf[UdpClusterManagerActor])), DiscoveryMgrId)
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
    OneForOneStrategy(maxNrOfRetries = maxRetries, withinTimeRange = maxRetriesTimewindow) {
      case x: Exception =>
        Restart
      case x =>
        Escalate
    }


  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[LeaderChanged])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }


  startWith(Initial, ServiceNodeData(
    joinStrategy = nodeCfg.asClass("node.cluster.join.strategy", classOf[RolePriorityStrategy]).newInstance().asInstanceOf[JoinStrategy]
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
      UnableToJoinCluster('seeds -> state.seedsToJoin)
      stop(FSM.Failure("Unable to join cluster, seeds: " + state.seedsToJoin))
    case Event(LeaderChanged(Some(a)), _) => transitionTo(Joined)
  }

  when(Joined) {
    case Event(CheckState, state: ServiceNodeData) => mergeWithExistingCluster(state) | stay()
  }

  onTransition {
    case _ -> ClusterDiscovery =>
      setTimer("timeout", DiscoveryTimeout, discoveryTimeout, repeat = false)
    case _ -> Joining =>
      val state = nextStateData.asInstanceOf[ServiceNodeData]
      cancelTimer("checkstate")
      setTimer("timeout", JoinTimeout, joinTimeout, repeat = false)
      JoiningCluster('seeds -> state.seedsToJoin)
      cluster.joinSeedNodes(state.seedsToJoin.toList)
    case _ -> ClusterFormationPending =>
      cancelTimer("timeout")
      setTimer("checkstate", CheckState, 300 millis, repeat = true)
    case _ -> Joined =>
      cancelTimer("timeout")
      cancelTimer("checkstate")
      JoinedCluster()
      if (!startServicesBeforeCluster) startProviders()
  }

  otherwise {
    case Event(s@ReachableClusters(our, other), state: ServiceNodeData) =>
      ClustersDiscovered('our -> our.map(_.toString), 'other -> other.map(_.toString).mkString(","))
      self ! CheckState
      stay using state.copy(reachableClusters = Some(s))

    case Event(ReachableNodes(ns), state: ServiceNodeData) =>
      AvailableSeeds('list -> ns)
      self ! CheckState
      stay using state.copy(availableSeeds = ns)

    case Event(Terminated(ref), _) if runningServices.contains(ref) =>
      stop(FSM.Failure("Service terminated " + ref))

    case Event(StopRequest, _) if runningServices.contains(sender()) =>
      StoppingService { ctx =>
        val actor = sender()
        context.unwatch(actor)
        ctx + ('ref -> actor)
        runningServices -= actor
        context.stop(actor)
        stay()
      }
  }


  private def joinExistingCluster(state: ServiceNodeData) = state.reachableClusters.flatMap { reachable =>
    state.joinStrategy.selectClusterToJoin(reachable.our, reachable.other) map { c => transitionTo(Joining) using state.copy(seedsToJoin = c.members.filter(_ != selfAddress), reachableClusters = None) }
  }

  private def ifSeedFormCluster(state: ServiceNodeData) =
    state.availableSeeds.toList.sortBy(_.toString).headOption.flatMap { seed =>
      if (seed == selfAddress) Some(transitionTo(Joining) using state.copy(seedsToJoin = Set(selfAddress))) else None
    }


  private def mergeWithExistingCluster(state: ServiceNodeData) = state.reachableClusters.map { reachable =>
    state.joinStrategy.selectClusterToJoin(reachable.our, reachable.other) match {
      case None => stay()
      case Some(c) =>
        ClusterMergeTrigger('other -> c)
        stop()
    }
  }


  private def startProvider(sm: ServiceMeta) = StartingService { ctx =>
    val actor = context.watch(context.actorOf(Props(Class.forName(sm.cl), sm.id), sm.id))
    ctx +('service -> sm.id, 'class -> sm.cl, 'ref -> actor)
    runningServices += actor
  }

  private def startProviders() = services foreach startProvider

}
