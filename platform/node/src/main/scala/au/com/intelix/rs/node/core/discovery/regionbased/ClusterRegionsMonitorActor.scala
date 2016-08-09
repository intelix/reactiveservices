package au.com.intelix.rs.node.core.discovery.regionbased

import akka.actor.{ActorRef, Props, Terminated}
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.evt.{EvtSource, InfoE}
import au.com.intelix.rs.core.actors.{ActorState, ClusterAwareness, StatefulActor}
import au.com.intelix.rs.node.core.discovery.DiscoveryMessages.ReachableClusters
import au.com.intelix.rs.node.core.discovery.ReachableCluster
import au.com.intelix.rs.node.core.discovery.regionbased.ClusterRegionsMonitorActor._

import scala.concurrent.duration._
import scala.language.postfixOps

object ClusterRegionsMonitorActor {

  object Evt {
    case object Cfg_Exposure extends InfoE
    case object Cfg_Regions extends InfoE

    case object RegionDiscovery_Off extends InfoE
    case object RegionDiscovery_Continuous extends InfoE
    case object RegionDiscovery_Paced extends InfoE
    case object RegionDiscovery_Fast extends InfoE
    case object RegionDiscovery_Started extends InfoE

  }

  case class RegionMeta(lookupManager: ActorRef, regionClusters: Option[Set[ReachableCluster]] = None)

  case class Data(regionLookups: Map[String, RegionMeta], blockedEndpoints: Set[Endpoint] = Set()) {
    def addClusters(regionId: String, clusters: Set[ReachableCluster]) =
      regionLookups.get(regionId).map { meta =>
        copy(regionLookups = regionLookups + (regionId -> meta.copy(regionClusters = Some(clusters))))
      }

    def allRegionsResponded = !regionLookups.values.exists(_.regionClusters.isEmpty)
  }

  object States {
    case object Idle extends ActorState
    case object Collecting extends ActorState
  }

}

class ClusterRegionsMonitorActor extends StatefulActor[Data] with ClusterAwareness {
  override val evtSource: EvtSource = "ClusterRegionsMonitorActor"

  val discoveryCfg = config.asConfig("node.cluster.discovery.region-based-http")
  val exposureEnabled = discoveryCfg.asBoolean("exposure.enabled", defaultValue = false)
  val endpointHost = discoveryCfg.asString("exposure.host", "localhost")
  val endpointPort = discoveryCfg.asInt("exposure.port", 0)
  val collectionIntervalThreshold = discoveryCfg.asFiniteDuration("collection-interval", 2 seconds)

  private val regions = discoveryCfg.asStringList("regions-required").toSet
  private val regionConfigs = regions.map { id =>
    val cfg = discoveryCfg.asConfig("region." + id)
    id -> cfg.asStringList("contacts").map(toEndpoint)
  }.toMap

  evt(Evt.Cfg_Exposure, 'enabled -> exposureEnabled, 'host -> endpointHost, 'port -> endpointPort)
  evt(Evt.Cfg_Regions, 'required -> regions)

  var exposureActor: Option[ActorRef] = startExposureActor()

  private def startExposureActor() = if (exposureEnabled) Some(context.actorOf(Props(classOf[ClusterStateExposureActor], endpointHost, endpointPort), "exposure")) else None

  private def startAll() = regionConfigs.map { case (id, list) => id -> RegionMeta(startLookupManagerFor(id, list)) }

  startWith(States.Idle, Data(startAll()))

  when(States.Idle) {
    case Event(RegionLookupActor.Out.ClustersAvailableInRegion(regionId, set), data) =>
      data.addClusters(regionId, set) match {
        case Some(newData) =>
          self ! Internal.Check
          transitionTo(States.Collecting) using newData forMax collectionIntervalThreshold
        case None => stay()
      }
  }
  when(States.Collecting) {
    case Event(RegionLookupActor.Out.ClustersAvailableInRegion(regionId, set), data) =>
      data.addClusters(regionId, set) match {
        case Some(newData) =>
          self ! Internal.Check
          stay() using newData
        case None => stay()
      }
    case Event(StateTimeout, data) =>
      self ! Internal.Publish
      transitionTo(States.Idle)
    case Event(Internal.Check, data) if data.allRegionsResponded =>
      self ! Internal.Publish
      transitionTo(States.Idle)

  }

  otherwise {
    case Event(b@TrafficBlocking.BlockCommunicationWith(host, port), data) =>
      if (endpointHost == host && endpointPort == port) {
        exposureActor.foreach(_ ! b)
      }
      stay()
    case Event(b@TrafficBlocking.UnblockCommunicationWith(host, port), data) =>
      if (endpointHost == host && endpointPort == port) {
        exposureActor.foreach(_ ! b)
      }
      stay()
    case Event(Internal.Check, data) => stay()
    case Event(Internal.Publish, data) =>
      publish(data)
      stay()
    case Event(Internal.RegionJoined(r), data) =>
      data.regionLookups get r match {
        case Some(meta) if !isContiniousDiscoveryEnabledFor(r) =>
          evt(Evt.RegionDiscovery_Off, 'region -> r, 'reason -> "Region present and region is remote")
          context.watch(meta.lookupManager) ! RegionLookupActor.In.Close
          stay()
        case Some(meta) =>
          evt(Evt.RegionDiscovery_Continuous, 'region -> r, 'reason -> "Region present and region is local")
          meta.lookupManager ! RegionLookupActor.In.ContinuousDiscovery
          stay()
        case None => stay()
      }
    case Event(Internal.RegionRemoved(r), data) =>
      data.regionLookups get r match {
        case Some(meta) if isClusterFormed =>
          meta.lookupManager ! RegionLookupActor.In.PacedDiscovery
          evt(Evt.RegionDiscovery_Paced, 'region -> r, 'reason -> "Region is not present but cluster is formed")
          stay()
        case Some(meta) =>
          meta.lookupManager ! RegionLookupActor.In.FastDiscovery
          evt(Evt.RegionDiscovery_Fast, 'region -> r, 'reason -> "Region is not present and cluster is not formed")
          stay()
        case None =>
          stay() using data.copy(regionLookups = data.regionLookups + (r -> RegionMeta(startLookupManagerFor(r, regionConfigs(r)))))
      }
    case Event(Internal.ClusterFormed, data) =>
      data.regionLookups.foreach {
        case (k, v) if clusterRoles.contains(k) => v.lookupManager ! RegionLookupActor.In.ContinuousDiscovery
        case (k, v) => v.lookupManager ! RegionLookupActor.In.PacedDiscovery
      }
      stay()
    case Event(Internal.ClusterBroken, data) =>
      data.regionLookups.values.foreach(_.lookupManager ! RegionLookupActor.In.FastDiscovery)
      stay()
    case Event(Terminated(a), data) if data.regionLookups.values.exists(_.lookupManager == a) =>
      val newData = data.regionLookups.find(_._2.lookupManager == a) match {
        case None => data
        case Some((id, m)) =>
          if (!clusterRoles.contains(id))
            data.copy(regionLookups = data.regionLookups + (id -> RegionMeta(startLookupManagerFor(id, regionConfigs(id)))))
          else
            data.copy(regionLookups = data.regionLookups - id)
      }
      stay() using newData
  }

  private def publish(data: Data) = {
    val ourMembers = allMembers.keys.toSet
    def ourCluster(c: ReachableCluster): Boolean = (c.members & ourMembers).nonEmpty
    val their = data.regionLookups.values.flatMap(_.regionClusters).flatten.toSet.filter(_.members.nonEmpty).filterNot(ourCluster)
    val our = if (ourMembers.nonEmpty) Some(ReachableCluster(ourMembers, clusterRoles, None)) else None
    context.parent ! ReachableClusters(our, their)
  }

  onClusterAnyMemberSetChanged {
    case _ => self ! Internal.Publish

  }
  onLeaderChanged {
    case Some(_) => self ! Internal.ClusterFormed
    case None => self ! Internal.ClusterBroken
  }

  onClusterRolesAdded {
    case roles if (roles & regions).nonEmpty =>
      (roles & regions).foreach(self ! Internal.RegionJoined(_))
  }

  onClusterRolesLost {
    case roles if (roles & regions).nonEmpty =>
      (roles & regions).foreach(self ! Internal.RegionRemoved(_))
  }

  private def isContiniousDiscoveryEnabledFor(regionId: String) = selfRoles.contains(regionId)

  private def startLookupManagerFor(id: String, list: List[Endpoint]) = {
    val actor = context.actorOf(Props(classOf[RegionLookupActor], id, list), s"lookup~$id")
    if (isClusterFormed) actor ! RegionLookupActor.In.PacedDiscovery
    evt(Evt.RegionDiscovery_Started, 'region -> id, 'endpoints -> list)
    actor
  }

  private def toEndpoint(s: String) = s.split(":") match {
    case Array(host, port) => Endpoint(host, port.toInt)
    case _ => throw new RuntimeException(s"Invalid contacts entry: $s")
  }

  private object Internal {
    case object Publish
    case object Check
    case object ClusterFormed
    case object ClusterBroken
    case class RegionJoined(id: String)
    case class RegionRemoved(id: String)
  }

}
