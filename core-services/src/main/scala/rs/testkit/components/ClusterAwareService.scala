package rs.testkit.components

import rs.core.actors.ClusterAwareness
import rs.core.evt.{EvtSource, InfoE}
import rs.core.services.StatelessServiceActor


object ClusterAwareService {
  val EvtSourceId = "Test.ClusterAwareService"

  case object EvtLeaderChanged extends InfoE

  case object EvtLeaderHandover extends InfoE

  case object EvtLeaderTakeover extends InfoE

  case object EvtMemberRemoved extends InfoE

  case object EvtMemberUnreachable extends InfoE

  case object EvtMemberUp extends InfoE

}


class ClusterAwareService(id: String) extends StatelessServiceActor(id) with ClusterAwareness {

  import ClusterAwareService._

  onLeaderChanged {
    case a => raise(EvtLeaderChanged, 'addr -> a)
  }

  onLeaderHandover {
    raise(EvtLeaderHandover)
  }

  onLeaderTakeover {
    raise(EvtLeaderTakeover)
  }

  onClusterMemberRemoved {
    case (a, r) => raise(EvtMemberRemoved, 'addr -> a, 'roles -> r)
  }

  onClusterMemberUnreachable {
    case (a, r) => raise(EvtMemberUnreachable, 'addr -> a, 'roles -> r)
  }

  onClusterMemberUp {
    case (a, r) => raise(EvtMemberUp, 'addr -> a, 'roles -> r)
  }

  override val evtSource: EvtSource = EvtSourceId
}

