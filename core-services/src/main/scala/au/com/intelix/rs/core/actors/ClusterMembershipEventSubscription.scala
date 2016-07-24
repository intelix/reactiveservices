package au.com.intelix.rs.core.actors

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

trait ClusterMembershipEventSubscription extends Actor {

  implicit val cluster = Cluster(context.system)

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[UnreachableMember], classOf[ReachableMember], classOf[LeaderChanged])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }


}
