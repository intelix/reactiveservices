package au.com.intelix.rs.node.core.discovery

import au.com.intelix.rs.core.actors.{ClusterAwareness, StatelessActor}
import au.com.intelix.evt.{EvtSource, InfoE}
import au.com.intelix.rs.node.core.discovery.ClusterWatcherActor._

object ClusterWatcherActor {
  object Evt {
    case object NewLeaderElected extends InfoE
    case object NodeUp extends InfoE
    case object NodeRemoved extends InfoE
    case object NodeUnreachable extends InfoE
  }
}

class ClusterWatcherActor extends StatelessActor with ClusterAwareness {
  override val evtSource: EvtSource = "ClusterWatcherActor"

  onLeaderChanged {
    case _ =>
      raise(Evt.NewLeaderElected, 'leader -> leader)
  }

  onClusterMemberUp {
    case (address, roles) =>
      raise(Evt.NodeUp, 'addr -> address.toString)
  }
  onClusterMemberRemoved {
    case (address, roles) =>
      raise(Evt.NodeRemoved, 'addr -> address.toString)
  }
  onClusterMemberUnreachable {
    case (address, roles) =>
      raise(Evt.NodeUnreachable, 'addr -> address.toString)
  }

}
