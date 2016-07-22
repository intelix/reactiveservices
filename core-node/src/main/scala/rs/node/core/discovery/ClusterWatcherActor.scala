package rs.node.core.discovery

import rs.core.actors.{ClusterAwareness, StatelessActor}
import rs.core.evt.{EvtSource, InfoE}
import rs.node.core.discovery.ClusterWatcherActor._

object ClusterWatcherActor {

  case object EvtNewLeaderElected extends InfoE

  case object EvtNodeUp extends InfoE

  case object EvtNodeRemoved extends InfoE

  case object EvtNodeUnreachable extends InfoE

}

class ClusterWatcherActor extends StatelessActor with ClusterAwareness {
  override val evtSource: EvtSource = "ClusterWatcherActor"

  onLeaderChanged {
    case _ =>
      raise(EvtNewLeaderElected, 'leader -> leader)
  }

  onClusterMemberUp {
    case (address, roles) =>
      raise(EvtNodeUp, 'addr -> address.toString)
  }
  onClusterMemberRemoved {
    case (address, roles) =>
      raise(EvtNodeRemoved, 'addr -> address.toString)
  }
  onClusterMemberUnreachable {
    case (address, roles) =>
      raise(EvtNodeUnreachable, 'addr -> address.toString)
  }

}
