package rs.testing.components

import rs.core.actors.ClusterAwareness
import rs.core.services.{ServiceCell, ServiceCellSysevents}
import rs.core.stream.StringStreamPublisher

object ClusterAwareServiceEvt extends ServiceCellSysevents {

  val LeaderChanged = "LeaderChanged".info
  val LeaderHandover = "LeaderHandover".info
  val LeaderTakeover = "LeaderTakeover".info

  val MemberRemoved = "MemberRemoved".info
  val MemberUnreachable = "MemberUnreachable".info
  val MemberUp = "MemberUp".info

  override def componentId: String = "Test"
}

class ClusterAwareService(id: String) extends ServiceCell(id) with ClusterAwareness with StringStreamPublisher {

  import ClusterAwareServiceEvt._

  onLeaderChanged {
    case a => LeaderChanged('addr -> a)
  }

  onLeaderHandover {
    LeaderHandover()
  }

  onLeaderTakeover {
    LeaderTakeover()
  }

  onClusterMemberRemoved {
    case (a, r) => MemberRemoved('addr -> a, 'roles -> r)
  }

  onClusterMemberUnreachable {
    case (a, r) => MemberUnreachable('addr -> a, 'roles -> r)
  }

  onClusterMemberUp {
    case (a, r) => MemberUp('addr -> a, 'roles -> r)
  }

}

