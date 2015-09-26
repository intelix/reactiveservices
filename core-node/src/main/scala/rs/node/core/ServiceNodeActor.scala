package rs.node.core

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import rs.core.actors._
import rs.core.config.ConfigOps.wrap
import rs.core.services.ServiceCell.StopRequest
import rs.node.core.ServiceNodeActor._
import rs.node.core.discovery.DiscoveryMessages.{ReachableClusters, ReachableNodes}
import rs.node.core.discovery.{JoinStrategy, RolePriorityStrategy, UdpClusterManagerActor}

import scala.collection.JavaConversions
import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.Scalaz._

object ServiceNodeActor {

  trait Evt extends BaseActorSysevents {

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

  object Evt extends Evt


  case class ServiceNodeData(joinStrategy: JoinStrategy, availableSeeds: Set[Address] = Set.empty, seedsToJoin: Set[Address] = Set.empty, reachableClusters: Option[ReachableClusters] = None)

  val DiscoveryMgrId = "discovery-mgr"

}


class ServiceNodeActor extends ActorWithData[ServiceNodeData] with Evt {

  private case object Start

  private case object JoinTimeout

  private case object DiscoveryTimeout

  private case object CheckState


  case object Initial extends ActorState

  case object ClusterDiscovery extends ActorState

  case object ClusterFormationPending extends ActorState

  case object Joining extends ActorState

  case object Joined extends ActorState

  case class ServiceMeta(id: String, cl: String)


  implicit val sys = context.system

  implicit val cluster = Cluster(context.system)

  private lazy val selfAddress = cluster.selfAddress


  private val discoveryManager = context.actorOf(Props(globalCfg.asClass("node.cluster.discovery.provider", classOf[UdpClusterManagerActor])), DiscoveryMgrId)
  private val joinTimeout = globalCfg.asFiniteDuration("node.cluster.join-timeout", 20 seconds)
  private val discoveryTimeout = globalCfg.asFiniteDuration("node.cluster.discovery.timeout", 10 seconds)


  private var runningServices: Set[ActorRef] = Set.empty
  private lazy val services: List[ServiceMeta] = JavaConversions.asScalaSet(config.getConfig("node.services").entrySet()).map {
    case e => ServiceMeta(e.getKey, e.getValue.unwrapped().toString)
  }.toList

  private val maxRetries = config.asInt("node.cluster.service-max-retries", -1)
  private val maxRetriesTimewindow: Duration = config.asFiniteDuration("node.cluster.service-max-retries-window", 1 days)
  private val startServicesBeforeCluster = config.asBoolean("node.start-services-before-cluster", defaultValue = false)

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
    joinStrategy = globalCfg.asClass("node.cluster.join.strategy", classOf[RolePriorityStrategy]).newInstance().asInstanceOf[JoinStrategy]
  ))

  self ! Start

  when(Initial) {
    case Event(Start, _) =>
      if (startServicesBeforeCluster) startProviders()
      transitionTo(ClusterDiscovery)
  }

  when(ClusterDiscovery) {
    case Event(DiscoveryTimeout, state) => transitionTo(ClusterFormationPending)
    case Event(CheckState, state) => joinExistingCluster(state) | stay()
  }

  when(ClusterFormationPending) {
    case Event(CheckState, state) =>
      joinExistingCluster(state) getOrElse ifSeedFormCluster(state) | stay()
  }

  when(Joining) {
    case Event(JoinTimeout, state) =>
      UnableToJoinCluster('seeds -> state.seedsToJoin)
      stop(FSM.Failure("Unable to join cluster, seeds: " + state.seedsToJoin))
    case Event(LeaderChanged(Some(a)), _) => transitionTo(Joined)
  }

  when(Joined) {
    case Event(CheckState, state) => mergeWithExistingCluster(state) | stay()
  }

  onTransition {
    case _ -> ClusterDiscovery =>
      setTimer("timeout", DiscoveryTimeout, discoveryTimeout, repeat = false)
    case _ -> Joining =>
      cancelTimer("checkstate")
      setTimer("timeout", JoinTimeout, joinTimeout, repeat = false)
      JoiningCluster('seeds -> nextStateData.seedsToJoin)
      cluster.joinSeedNodes(nextStateData.seedsToJoin.toList)
    case _ -> ClusterFormationPending =>
      cancelTimer("timeout")
      setTimer("checkstate", CheckState, 300 millis, repeat = true)
    case _ -> Joined =>
      cancelTimer("timeout")
      cancelTimer("checkstate")
      JoinedCluster()
      if (!startServicesBeforeCluster) startProviders()
  }

  whenUnhandledChained {
    case Event(s@ReachableClusters(our, other), state) =>
      ClustersDiscovered('our -> our.map(_.toString), 'other -> other.map(_.toString).mkString(","))
      self ! CheckState
      stay using state.copy(reachableClusters = Some(s))

    case Event(ReachableNodes(ns), state) =>
      AvailableSeeds('list -> ns)
      self ! CheckState
      stay using state.copy(availableSeeds = ns)

    case Event(Terminated(ref), _) if runningServices.contains(ref) =>
      stop(FSM.Failure("Service terminated " + ref))

    case Event(StopRequest, _) if runningServices.contains(sender()) =>
      StoppingService { ctx =>
        val actor = sender()
        context.unwatch(actor)
        ctx +('ref -> actor)
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
