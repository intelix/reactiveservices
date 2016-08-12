package au.com.intelix.rs.node.core.discovery.regionbased

import akka.actor.{ActorRef, AddressFromURIString, Props, Terminated}
import au.com.intelix.evt.{InfoE, TraceE}
import au.com.intelix.rs.core.actors.StatelessActor
import au.com.intelix.rs.node.core.discovery.ReachableCluster
import au.com.intelix.rs.node.core.discovery.regionbased.RegionLookupActor._
import com.typesafe.scalalogging.StrictLogging

import scala.language.postfixOps

object RegionLookupActor {

  object Evt {
    case object Closing extends InfoE
    case object ConnectionTerminated extends InfoE
    case object DataSkipped extends TraceE
    case object DataForwarded extends TraceE
  }

  object In {
    case object Close
    case object ContinuousDiscovery
    case object PacedDiscovery
    case object FastDiscovery
  }

  object Out {
    case class ClustersAvailableInRegion(regionId: String, set: Set[ReachableCluster])
  }

}


class RegionLookupActor(regionId: String, contacts: List[Endpoint]) extends StatelessActor with StrictLogging {


  override def evtSourceSubId: Option[String] = Some(regionId)
  commonEvtFields('region -> regionId)

  case class Data(ep: Endpoint, lastView: Option[RegionEndpointConnectorActor.Out.View] = None)

  var connectors: Map[ActorRef, Data] = contacts.map { e =>
    context.watch(context.actorOf(Props(classOf[RegionEndpointConnectorActor], regionId, e), s"endpoint~${e.host}_${e.port}")) -> Data(e)
  }.toMap

  self ! Internal.Check

  var lastUpdate: Option[Out.ClustersAvailableInRegion] = None

  onMessage {
    case In.Close =>
      evt(Evt.Closing, 'reason -> "Requested")
      connectors.keys.foreach(_ ! RegionEndpointConnectorActor.In.Close)
      self ! Internal.Check
    case Terminated(actor) =>
      connectors get actor foreach { d =>
        evt(Evt.ConnectionTerminated, 'endpoint -> d.ep)
      }
      connectors -= actor
      self ! Internal.Check
    case RegionEndpointConnectorActor.Out.Invalidate =>
      connectors get sender() match {
        case Some(data@Data(_, Some(st))) =>
          connectors += sender() -> data.copy(lastView = None)
          republish()
        case _ =>
      }
    case r: RegionEndpointConnectorActor.Out.View =>
      connectors get sender() match {
        case Some(Data(ep, Some(st))) if st == r =>
          evt(Evt.DataSkipped, 'reason -> "no changes", 'endpoint -> ep)
        case Some(data) =>
          connectors += sender() -> data.copy(lastView = Some(r))
          evt(Evt.DataForwarded, 'from -> data.ep)
          republish()
        case None =>
          evt(Evt.DataSkipped, 'reason -> "Unknown connector", 'actor -> sender())
      }
    case Internal.Check =>
      republish()
      if (connectors.isEmpty) {
        evt(Evt.Closing, 'reason -> "No more active connectors")
        context.stop(self)
      }
    case In.ContinuousDiscovery => connectors.keys.foreach(_ ! RegionEndpointConnectorActor.In.ContinuousDiscovery)
    case In.FastDiscovery => connectors.keys.foreach(_ ! RegionEndpointConnectorActor.In.FastDiscovery)
    case In.PacedDiscovery => connectors.keys.foreach(_ ! RegionEndpointConnectorActor.In.PacedDiscovery)
  }

  private def republish(): Unit = {
    val list = connectors.values.flatMap(_.lastView).toList
    val set = list match {
      case l if l.isEmpty => Set[ReachableCluster]()
      case l => l.map {
        r => ReachableCluster(r.members.map(AddressFromURIString(_)), r.roles, None)
      }.toSet
    }
    val msg = Out.ClustersAvailableInRegion(regionId, set)
    if (!lastUpdate.contains(msg)) {
      lastUpdate = Some(msg)
      context.parent ! msg
    }
  }

  object Internal {

    case object Check

  }


}
