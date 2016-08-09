package au.com.intelix.rs.core.testkit.components

import au.com.intelix.rs.core.actors.ClusterAwareness
import au.com.intelix.evt.{EvtSource, InfoE}
import au.com.intelix.rs.core.services.StatelessServiceActor


object ClusterAwareService {

  object Evt {
    case object LeaderChanged extends InfoE
    case object LeaderHandover extends InfoE
    case object LeaderTakeover extends InfoE
    case object MemberRemoved extends InfoE
    case object MemberUnreachable extends InfoE
    case object MemberUp extends InfoE
  }

}


class ClusterAwareService extends StatelessServiceActor with ClusterAwareness {

  import ClusterAwareService._

  onLeaderChanged {
    case a => raise(Evt.LeaderChanged, 'addr -> a)
  }

  onLeaderHandover {
    raise(Evt.LeaderHandover)
  }

  onLeaderTakeover {
    raise(Evt.LeaderTakeover)
  }

  onClusterMemberRemoved {
    case (a, r) => raise(Evt.MemberRemoved, 'addr -> a, 'roles -> r)
  }

  onClusterMemberUnreachable {
    case (a, r) => raise(Evt.MemberUnreachable, 'addr -> a, 'roles -> r)
  }

  onClusterMemberUp {
    case (a, r) => raise(Evt.MemberUp, 'addr -> a, 'roles -> r)
  }

}

