package rs.testkit.components

import rs.core.actors.ClusterAwareness
import rs.core.services.{ServiceEvt, StatelessServiceActor}

trait ClusterAwareServiceEvt extends ServiceEvt {

  val LeaderChanged = "LeaderChanged".info
  val LeaderHandover = "LeaderHandover".info
  val LeaderTakeover = "LeaderTakeover".info

  val MemberRemoved = "MemberRemoved".info
  val MemberUnreachable = "MemberUnreachable".info
  val MemberUp = "MemberUp".info

  override def componentId: String = "Test.ClusterAwareService"
}

object ClusterAwareServiceEvt extends ClusterAwareServiceEvt

class ClusterAwareService(id: String) extends StatelessServiceActor(id) with ClusterAwareness with ClusterAwareServiceEvt {

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

