package rs.node.core.discovery.tcp

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import com.typesafe.scalalogging.StrictLogging
import rs.core.actors.{ActorState, ClusterAwareness, StatefulActor}
import rs.core.config.ConfigOps.wrap
import rs.core.evt.EvtSource
import rs.node.core.discovery.DiscoveryMessages.ReachableClusters
import rs.node.core.discovery.ReachableCluster
import rs.node.core.discovery.tcp.ClusterRegionsMonitorActor._

import scala.concurrent.duration._
import scala.language.postfixOps

object ClusterRegionsMonitorActor {

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

class ClusterRegionsMonitorActor extends StatefulActor[Data] with ClusterAwareness with StrictLogging {
  override val evtSource: EvtSource = "ClusterRegionsMonitorActor"

  val exposureEnabled = config.asBoolean("node.cluster.discovery.exposure.enabled", defaultValue = false)
  val endpointHost = config.asString("node.cluster.discovery.exposure.host", "localhost")
  val endpointPort = config.asInt("node.cluster.discovery.exposure.port", 0)

  private val regions = nodeCfg.asStringList("node.cluster.discovery.regions-required").toSet
  private val regionConfigs = regions.map { id =>
    val cfg = nodeCfg.asConfig("node.cluster.discovery.region." + id)
    id -> cfg.asStringList("contacts").map(toEndpoint)
  }.toMap

  var exposureActor: Option[ActorRef] = startExposureActor()

  private def startExposureActor() = if (exposureEnabled) Some(context.actorOf(Props(classOf[ClusterViewExposureActor], endpointHost, endpointPort), "exposure")) else None

  private def startAll() = regionConfigs.map { case (id, list) => id -> RegionMeta(startLookupManagerFor(id, list)) }

  startWith(States.Idle, Data(startAll()))

  when(States.Idle) {
    case Event(RegionLookupActor.Out.ClustersAvailableInRegion(regionId, set), data) =>
      data.addClusters(regionId, set) match {
        case Some(newData) =>
          self ! Internal.Check
          transitionTo(States.Collecting) using newData forMax (2 seconds) // TODO configurable
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
    case Event(TrafficBlocking.BlockCommunicationWith(host, port), data) =>
      if (endpointHost == host && endpointPort == port) {
        exposureActor.foreach(context.stop)
        exposureActor = None
      }
      stay()
    case Event(TrafficBlocking.UnblockCommunicationWith(host, port), data) =>
      if (endpointHost == host && endpointPort == port && exposureActor.isEmpty) {
        exposureActor = startExposureActor()
      }
      stay()
    case Event(Internal.Check, data) => stay()
    case Event(Internal.Publish, data) =>
      publish(data)
      stay()
    case Event(Internal.RegionJoined(r), data) =>
      data.regionLookups get r match {
        case Some(meta) =>
          logger.info(s"!>>> Shutting down region lookup manager $r")
          meta.lookupManager ! RegionLookupActor.In.Close
          self ! Internal.Publish
          stay() using data.copy(regionLookups = data.regionLookups - r)
        case None => stay()
      }
    case Event(Internal.RegionRemoved(r), data) =>
      data.regionLookups get r match {
        case Some(meta) => stay()
        case None =>
          stay() using data.copy(regionLookups = data.regionLookups + (r -> RegionMeta(startLookupManagerFor(r, regionConfigs.get(r).get))))
      }
  }

  private def publish(data: Data) = {
    val ourMembers = allMembers.keys.toSet
    def ourCluster(c: ReachableCluster): Boolean = (c.members & ourMembers).nonEmpty
    val their = data.regionLookups.values.flatMap(_.regionClusters).flatten.toSet.filter(_.members.nonEmpty).filterNot(ourCluster)
    val our = if (ourMembers.nonEmpty) Some(ReachableCluster(ourMembers, clusterRoles, None)) else None
    context.parent ! ReachableClusters(our, their)
  }

  onClusterRolesAdded {
    case roles if (roles & regions).nonEmpty =>
      logger.info(s"!>>> Added role of interest: ${roles & regions}")
      (roles & regions).foreach(self ! Internal.RegionJoined(_))
  }

  onClusterRolesLost {
    case roles if (roles & regions).nonEmpty =>
      logger.info(s"!>>> Lost role of interest: ${roles & regions}")
      (roles & regions).foreach(self ! Internal.RegionRemoved(_))
  }

  private def startLookupManagerFor(id: String, list: List[Endpoint]) = {
    logger.info(s"!>>> Starting region lookup manager for $id")
    context.actorOf(Props(classOf[RegionLookupActor], id, list), s"lookup~$id")
  }

  private def toEndpoint(s: String) = s.split(":") match {
    case Array(host, port) => Endpoint(host + ":" + port, host, port.toInt, new InetSocketAddress(host, port.toInt))
    case _ => throw new RuntimeException(s"Invalid contacts entry: $s")
  }

  private object Internal {

    case object Publish

    case object Check

    case class RegionJoined(id: String)

    case class RegionRemoved(id: String)

  }

}
