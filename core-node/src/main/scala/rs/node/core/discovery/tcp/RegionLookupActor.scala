package rs.node.core.discovery.tcp

import akka.io.{IO, Tcp}
import rs.core.actors.{StatefulActor, StatelessActor}
import rs.core.evt.EvtSource
import RegionLookupActor._
import akka.actor.{ActorRef, AddressFromURIString, Props, Terminated}
import com.typesafe.scalalogging.StrictLogging
import rs.node.core.discovery.ReachableCluster

import scala.concurrent.duration._
import scala.language.postfixOps

object RegionLookupActor {

  object In {

    case object Close

  }

  object Out {

    case class ClustersAvailableInRegion(regionId: String, set: Set[ReachableCluster])

  }

}


class RegionLookupActor(regionId: String, contacts: List[Endpoint]) extends StatelessActor with StrictLogging {
  override val evtSource: EvtSource = s"RegionLookup.$regionId"

  case class Data(ep: Endpoint, lastView: Option[RegionEndpointConnectorActor.Out.View] = None)

  var connectors: Map[ActorRef, Data] = contacts.map { e =>
    context.watch(context.actorOf(Props(classOf[RegionEndpointConnectorActor], regionId, e), s"endpoint~${e.addr.getHostName}_${e.addr.getPort}")) -> Data(e)
  }.toMap

  self ! Internal.Check


  onMessage {
    case In.Close =>
      connectors.keys.foreach(_ ! RegionEndpointConnectorActor.In.Close)
      self ! Internal.Check
    case Terminated(actor) =>
      connectors -= actor
      logger.info(s"Connector terminated $actor")
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
        case Some(Data(_, Some(st))) if st == r => // same view received, ignored
        case Some(data) =>
          connectors += sender() -> data.copy(lastView = Some(r))
          republish()
        case None =>
          logger.warn(s"Received $r from unregistered connector ${sender()}, ignored")
      }
    case Internal.Check =>
      republish()
      if (connectors.isEmpty) {
        logger.info("All connectors terminated, stopping now")
        context.stop(self)
      }
  }

  private def republish(): Unit = {
    val set = connectors.values.flatMap(_.lastView).toList.map {
      r => ReachableCluster(r.members.map(AddressFromURIString(_)), r.roles, None)
    }.toSet
    context.parent ! Out.ClustersAvailableInRegion(regionId, set)
  }

  object Internal {

    case object Check

  }


}
