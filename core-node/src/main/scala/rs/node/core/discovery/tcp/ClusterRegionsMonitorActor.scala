package rs.node.core.discovery.tcp

import java.net.InetSocketAddress

import akka.remote.ContainerFormats.ActorRef
import rs.core.actors.{ActorState, ClusterAwareness, StatefulActor}
import rs.core.config.ConfigOps.wrap
import rs.core.evt.EvtSource
import rs.node.core.discovery.UdpClusterManagerActor.Endpoint


private case class Data(regionDelegates: Map[String, ActorRef] = Map())

private object States {

  case object Initial extends ActorState

}


class ClusterRegionsMonitorActor extends StatefulActor[Data] with ClusterAwareness {
  override val evtSource: EvtSource = "ClusterRegionsMonitorActor"

  private val regions = nodeCfg.asStringList("node.cluster.discovery.regions-required").toSet
  private val regionConfigs = regions.map { id =>
    val cfg = nodeCfg.asConfig("node.cluster.discovery.region." + id)
    id -> cfg.asStringList("contacts").map(toEndpoint)
  }.toMap




  implicit val sys = context.system

  startWith(States.Initial, Data())

  when(States.Initial) {
    case
  }

  onTransition {
    case _ -> States.Initial =>
  }

  onClusterRolesAdded {
    case roles if (roles & regions).nonEmpty =>
      log.info(s"!>>> Added role of interest: ${roles & regions}")
      (roles & regions).foreach(self ! Internal.RegionJoined(_))
  }

  onClusterRolesLost {
    case roles if (roles & regions).nonEmpty =>
      log.info(s"!>>> Lost role of interest: ${roles & regions}")
      (roles & regions).foreach(self ! Internal.RegionRemoved(_))
  }

  private def toEndpoint(s: String) =     s.split(":") match {
    case Array(hostOnly) => Endpoint(hostOnly + ":" + udpEndpointPort, hostOnly, udpEndpointPort, new InetSocketAddress(hostOnly, udpEndpointPort))
    case Array(host, port) => Endpoint(host + ":" + port, host, port.toInt, new InetSocketAddress(host, port.toInt))
    case _ => throw new RuntimeException(s"Invalid udp-contacts entry: $s")
  }

  private object Internal {

    case object Check
    case class RegionJoined(id: String)
    case class RegionRemoved(id: String)

  }

}
