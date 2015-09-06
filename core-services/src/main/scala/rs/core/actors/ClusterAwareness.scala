package rs.core.actors

import akka.actor.Address
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

trait ClusterAwareness extends ActorWithComposableBehavior {

  implicit val cluster = Cluster(context.system)

  var reachableMembers: Set[Address] = Set.empty

  var leader: Option[Address] = None

  def isClusterLeader = leader.contains(cluster.selfAddress)
  def isAddressReachable(address: Address) = reachableMembers contains address

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, initialStateMode = InitialStateAsSnapshot,
      classOf[MemberEvent], classOf[UnreachableMember], classOf[ReachableMember], classOf[LeaderChanged])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }

  def onClusterStateSnapshot(): Unit = {}

  def onClusterMemberUp(address: Address, roles: Set[String]): Unit = {}

  def onClusterMemberUnreachable(address: Address, roles: Set[String]): Unit = {}

  def onClusterMemberRemoved(address: Address, roles: Set[String]): Unit = {}

  def onLeaderHandover(): Unit = {}

  def onLeaderTakeover(): Unit = {}

  def onLeaderChanged(): Unit = {}

  private def processLeaderChange(l: Option[Address]) =
    if (l != leader) {
      val wasLeader = isClusterLeader
      leader = l
      if (wasLeader && !isClusterLeader) onLeaderHandover()
      if (!wasLeader && isClusterLeader) onLeaderTakeover()
      onLeaderChanged()
    }



  onMessage {
    case CurrentClusterState(m, u, _, l, _) =>
      m.foreach { member =>
        onClusterMemberUp(member.address, member.roles)
      }
      processLeaderChange(l)
      onClusterStateSnapshot()
    case MemberUp(member) =>
      reachableMembers = reachableMembers + member.address
      onClusterMemberUp(member.address, member.roles)
    case UnreachableMember(member) =>
      reachableMembers = reachableMembers - member.address
      onClusterMemberUnreachable(member.address, member.roles)
    case ReachableMember(member) =>
      reachableMembers = reachableMembers + member.address
      onClusterMemberUp(member.address, member.roles)
    case MemberRemoved(member, previousStatus) =>
      reachableMembers = reachableMembers - member.address
      onClusterMemberRemoved(member.address, member.roles)
    case LeaderChanged(l) => processLeaderChange(l)
  }

}
