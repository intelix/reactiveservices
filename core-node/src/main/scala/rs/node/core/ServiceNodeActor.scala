package rs.node.core

import akka.actor.{ActorRef, Address, FSM, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import com.typesafe.config._
import rs.core.sysevents.SyseventOps.stringToSyseventOps
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import net.ceedubs.ficus.Ficus._
import rs.core.actors.{ActorUtils, BaseActorSysevents}
import rs.core.tools.UUIDTools
import rs.core.tools.metrics.WithCHMetrics
import rs.node.core.ServiceNodeActor._

import scala.collection.JavaConversions
import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.Scalaz._

trait ServiceNodeSysevents extends ComponentWithBaseSysevents with BaseActorSysevents {

  val StartingCluster = "StartingCluster".info
  val StoppingCluster = "StoppingCluster".info

  override def componentId: String = "Cluster.Node"
}

object ServiceNodeActor {

  sealed trait ClusterState

  case object ClusterStateNotJoined extends ClusterState

  case object ClusterStateUnstable extends ClusterState

  case object ClusterStateStable extends ClusterState


  sealed trait MemberState

  case object MemberStateUp extends MemberState

  case object MemberStateUnavailable extends MemberState

  case object PerformHandshake

  case object CheckState

  case object Start

  case object PerformJoinIfPossible

  case class ClusterView(correlationId: String, formed: Boolean, activeMembers: Set[Address])

  case class ClusterViewEnquiry(correlationId: String)

  case class NodePerspective(addr: String, clusterView: Option[ClusterView])

  case class ServiceMeta(id: String, cl: String)

  private case object DiscoveryTimeout

  private case object JoinTimeout

  sealed trait State

  case object Initial extends State

  case object DetachedWaitingForAllSeeds extends State

  case object DetachedWaitingForFirstSeed extends State

  case object AwaitingJoin extends State

  case object PartiallyBuilt extends State

  case object FullyBuilt extends State

  case class Data(selfAddress: String,
                  clusterNodes: List[String],
                  clusterGuardian: Option[ActorRef] = None,
                  perspectives: Map[String, NodePerspective] = Map.empty,
                  members: Map[Address, MemberState] = Map.empty,
                  leader: Option[Address] = None,
                  seeds: List[Address] = List.empty) {

    def isReceivedResponsesFromAllNodes = perspectives.nonEmpty && perspectives.values.forall(_.clusterView.isDefined)

    def isFormedClusterFound = perspectives.values.exists(_.clusterView.exists(_.formed))

    def isHigherPriorityNodeAlive =
      clusterNodes.takeWhile(_ != selfAddress).exists { node => perspectives.values.find(_.addr.equalsIgnoreCase(node)).exists(_.clusterView.isDefined) }

    def locateMembersOfAnotherLargestFormedCluster: Option[Set[Address]] =
      perspectives.foldLeft[Option[Set[Address]]](None) {
        case (pick, (a, NodePerspective(addr, Some(cv)))) if cv.formed && !isBelongToMyCluster(cv.activeMembers) =>
          pick match {
            case Some(set) if set.size >= cv.activeMembers.size => pick
            case _ => Some(cv.activeMembers)
          }
        case (pick, _) => pick
      }

    def isBelongToMyCluster(activeMembers: Set[Address]) = activeMembers.exists(members.contains)

    //activeMembers.exists { next => isMyMember(next.toString) }

    def hasMembers = members.nonEmpty

    def hasLeader = leader.isDefined

    def hasUnreachableMembers = members.exists(_._2 == MemberStateUnavailable)

    def currentClusterState =
      if (!hasMembers) ClusterStateNotJoined
      else if (!hasLeader || hasUnreachableMembers) ClusterStateUnstable
      else ClusterStateStable

    def isExpectedMemberMissing = clusterNodes.exists { n => !members.exists(_._1.toString.equalsIgnoreCase(n)) }

    def isAllExpectedMembersPresent = !isExpectedMemberMissing

    def shouldMergeWith(candidateMembers: Set[Address]): Boolean =
      candidateMembers.size > members.size || haveHigherPriorityMember(candidateMembers)

    def isMyMember(node: String) = selfAddress.equalsIgnoreCase(node) || members.exists(_._1.toString.equalsIgnoreCase(node))

    def isNotMyMember(node: String) = !isMyMember(node)

    def haveHigherPriorityMember(candidateMembers: Set[Address]): Boolean =
      clusterNodes takeWhile isNotMyMember exists { node => candidateMembers.exists(_.toString.equalsIgnoreCase(node)) }

    def missingClusterNodes = clusterNodes.filter(isNotMyMember)

    def addResponse(response: ClusterView) =
      this.copy(perspectives =
        perspectives map {
          case (k, v) if k == response.correlationId => k -> v.copy(clusterView = Some(response))
          case (k, v) => k -> v
        })

  }

}

class ServiceNodeActor
  extends ServiceNodeSysevents
  with WithSyseventPublisher
  with ActorUtils
  with FSM[State, Data]
  with WithCHMetrics {

  // !>>>> TODO Remove
  //  val reporter = Slf4jReporter.forRegistry(metricRegistry)
  //    .convertRatesTo(TimeUnit.SECONDS)
  //    .convertDurationsTo(TimeUnit.MILLISECONDS)
  //    .build()
  //
  //  reporter.start(10, TimeUnit.SECONDS)


  //  val reporter2 = CsvReporter.forRegistry(metricRegistry)
  //    .convertRatesTo(TimeUnit.SECONDS)
  //    .convertDurationsTo(TimeUnit.MILLISECONDS)
  //    .build(new java.io.File(System.getProperty("csvout")))
  //
  //  reporter2.start(10, TimeUnit.SECONDS)


  implicit val cluster = Cluster(context.system)
  private val selfAddress = cluster.selfAddress

  lazy val clusterSystemId = config.as[Option[String]]("node.cluster.system-id") | "cluster"

  lazy val clusterNodes = config.as[Option[List[String]]]("node.cluster.core-nodes") match {
    case None => List(selfAddress.toString)
    case Some(l) if l.isEmpty => List(selfAddress.toString)
    case Some(l) => l.map(s"akka.tcp://$clusterSystemId@" + _)
  }

  lazy val services: List[ServiceMeta] = JavaConversions.asScalaSet(config.getConfig("node.services").entrySet()).map {
    case e => ServiceMeta(e.getKey, e.getValue.unwrapped().toString)
  }.toList


  private def controllerSelectorAt(addr: String) = context.actorSelection(addr + context.self.path.toStringWithoutAddress)

  private def contactNode(node: String) = {
    val correlation = UUIDTools.generateShortUUID
    //    println(s"!>>> controllerSelectorAt = " + controllerSelectorAt(node))
    controllerSelectorAt(node) ! ClusterViewEnquiry(correlation)
    correlation -> NodePerspective(node, None)
  }


  private def isHigherPriority(addr: Address, otherAddr: Address): Boolean =
    (clusterNodes.indexOf(addr.toString), clusterNodes.indexOf(otherAddr.toString)) match {
      case (_, -1) => false
      case (-1, _) => true
      case (a, b) => a > b
    }


  private def startProvider(sm: ServiceMeta) = {
    //    log.info("!>>>> Starting " + sm)
    context.actorOf(Props(Class.forName(sm.cl), sm.id))
    //    log.info("!>>>> Started " + sm)
  }

  private def startProviders() = services foreach startProvider


  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    //    println("!>>> Hello from ServiceNodeActor")
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[LeaderChanged],
      classOf[MemberUp],
      classOf[UnreachableMember],
      classOf[ReachableMember],
      classOf[MemberRemoved],
      classOf[MemberExited]
    )
  }


  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }


  startWith(Initial, Data(selfAddress = selfAddress.toString, clusterNodes = clusterNodes))

  when(Initial) {
    case Event(Start, t) => goto(DetachedWaitingForAllSeeds) using t.copy(clusterGuardian = Some(sender()))
  }

  when(DetachedWaitingForAllSeeds) {
    case Event(DiscoveryTimeout, _) =>
      //      println("!>>>> DetachedWaitingForAllSeeds timeout")
      goto(DetachedWaitingForFirstSeed)
    case Event(CheckState, t: Data) =>
      //      println("!>>> CheckState in DetachedWaitingForAllSeeds")
      if (t.isReceivedResponsesFromAllNodes) self ! PerformJoinIfPossible
      stay()
  }

  when(DetachedWaitingForFirstSeed) {
    case Event(CheckState, t: Data) =>
      self ! PerformJoinIfPossible
      stay()
  }

  when(AwaitingJoin) {
    case Event(JoinTimeout, t) => stop(FSM.Failure("Unable to join cluster, seeds: " + t.seeds))
    case Event(PerformJoinIfPossible, t: Data) => stay()
    case Event(CheckState, t: Data) => t.currentClusterState match {
      case ClusterStateStable | ClusterStateUnstable =>
        if (t.isExpectedMemberMissing) goto(PartiallyBuilt) else goto(FullyBuilt)
      case _ => stay()
    }
  }

  when(PartiallyBuilt) {
    case Event(CheckState, t) =>
      if (t.isAllExpectedMembersPresent) goto(FullyBuilt)
      else if (t.isFormedClusterFound && t.locateMembersOfAnotherLargestFormedCluster.exists(t.shouldMergeWith)) {

        stop()
      } else stay()
    case Event(e: ClusterViewEnquiry, t: Data) =>
      sender() ! ClusterView(e.correlationId, formed = true, t.members.keySet)
      stay()
  }

  when(FullyBuilt) {
    case Event(CheckState, t) => if (t.isExpectedMemberMissing) goto(PartiallyBuilt) else stay()
    case Event(e: ClusterViewEnquiry, t: Data) =>
      sender() ! ClusterView(e.correlationId, formed = true, t.members.keySet)
      stay()
  }

  whenUnhandled {
    case Event(PerformHandshake, t: Data) =>
      stay using t.copy(perspectives = t.missingClusterNodes.map(contactNode).toMap)
    case Event(e: ClusterView, t: Data) =>
      self ! CheckState
      goto(stateName) using t.addResponse(e)
    case Event(e: ClusterViewEnquiry, t: Data) =>
      sender() ! ClusterView(e.correlationId, formed = false, Set.empty)
      stay()
    case Event(PerformJoinIfPossible, t: Data) =>
      if (t.isFormedClusterFound) {
        t.locateMembersOfAnotherLargestFormedCluster match {
          case Some(members) => goto(AwaitingJoin) using t.copy(seeds = members.toList.filter(_ != selfAddress).sortWith(isHigherPriority), perspectives = Map.empty)
          case None => stay()
        }
      } else if (!t.isHigherPriorityNodeAlive) {
        goto(AwaitingJoin) using t.copy(seeds = List(selfAddress), perspectives = Map.empty)
      } else stay()
    case Event(LeaderChanged(leader), t) =>
      self ! CheckState
      stay using t.copy(leader = leader)
    case Event(MemberExited(member), t) =>
      self ! CheckState
      stay using t.copy(members = t.members - member.address)
    case Event(MemberRemoved(member, _), t) =>
      self ! CheckState
      stay using t.copy(members = t.members - member.address)
    case Event(UnreachableMember(member), t) =>
      self ! CheckState
      stay using t.copy(members = t.members + (member.address -> MemberStateUnavailable))
    case Event(ReachableMember(member), t) =>
      self ! CheckState
      stay using t.copy(members = t.members + (member.address -> MemberStateUp))
    case Event(MemberUp(member), t) =>
      self ! CheckState
      stay using t.copy(members = t.members + (member.address -> MemberStateUp))
    case Event(DiscoveryTimeout, _) => stay()
    case Event(JoinTimeout, _) => stay()
    case Event(x, _) => println(s"!>>> $x in $stateName")
      stay()
  }

  onTransition {
    case _ -> DetachedWaitingForAllSeeds =>
      //      println("!>>>> -> DetachedWaitingForAllSeeds")
      setTimer("timeout", DiscoveryTimeout, 10 seconds, repeat = false)
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 3 seconds, repeat = true)
    case _ -> DetachedWaitingForFirstSeed =>
      //      println("!>>>> -> DetachedWaitingForFirstSeed")
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 3 seconds, repeat = true)
    case _ -> AwaitingJoin =>
      setTimer("timeout", JoinTimeout, 20 seconds, repeat = false)
      cancelTimer("handshake")
      setTimer("checkState", CheckState, 1 seconds, repeat = true)
      //      log.info(s"!>>>> Joining ${nextStateData.seeds}")
      cluster.joinSeedNodes(nextStateData.seeds)
    case AwaitingJoin -> PartiallyBuilt =>
      //      log.info(s"!>>>> AwaitingJoin -> PartiallyBuilt")
      cancelTimer("timeout")
      startProviders()
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 3 seconds, repeat = true)
    case _ -> PartiallyBuilt =>
      //      log.info(s"!>>>> ? -> PartiallyBuilt")
      setTimer("handshake", PerformHandshake, 4 seconds, repeat = true)
      setTimer("checkState", CheckState, 3 seconds, repeat = true)
    case AwaitingJoin -> FullyBuilt =>
      //      log.info(s"!>>>> AwaitingJoin -> FullyBuilt")
      cancelTimer("timeout")
      startProviders()
      cancelTimer("handshake")
      cancelTimer("checkState")
    case _ -> FullyBuilt =>
      //      log.info(s"!>>>> ? -> FullyBuilt")
      cancelTimer("handshake")
      cancelTimer("checkState")
  }

  initialize()

}

