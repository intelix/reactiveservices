/*
 * Copyright 2014-16 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rs.node.core.discovery

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Address, AddressFromURIString}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.io.{IO, Udp}
import akka.util.ByteString
import rs.core.actors.{ActorState, CommonActorEvt, StatefulActor}
import rs.core.config.ConfigOps.wrap
import rs.node.core.discovery.DiscoveryMessages.{ReachableClusters, ReachableNodes}
import rs.node.core.discovery.UdpClusterManagerActor._

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps


trait UdpClusterManagerActorEvt extends CommonActorEvt {

  val UdpBound = "UdpBound" info

  val NewLeaderElected = "NewLeaderElected".info
  val NodeUp = "NodeUp".info
  val NodeRemoved = "NodeRemoved".info
  val NodeExited = "NodeExited".info
  val NodeUnreachable = "NodeUnreachable".info
  val NodeReachable = "NodeReachable".info

  override def componentId: String = "UdpClusterManager"
}

object UdpClusterManagerActorEvt extends UdpClusterManagerActorEvt


object UdpClusterManagerActor {


  object UdpMessages {

    case class Request(node: String) {
      lazy val toByteString = ByteString("?" + node)
    }

    object Request {
      def unapply(bs: ByteString): Option[String] =
        bs.utf8String match {
          case s if s.startsWith("?") => Some(s.drop(1))
          case _ => None
        }
    }

    case class Response(node: String, address: String, members: Set[String], uptime: Long, roles: Set[String], seed: Option[Address]) {
      lazy val toByteString = ByteString("!" + node + ";" + address + ";" + seed.map(_.toString).getOrElse("none") + ";" + (if (members.isEmpty) "none" else members.mkString(",")) + ";" + uptime + ";" + roles.mkString(","))
    }

    object Response {
      private def convertSeed(str: String) = str match {
        case "none" => None
        case s => Some(AddressFromURIString(s))
      }

      def unapply(bs: ByteString): Option[(String, String, Set[String], Long, Set[String], Option[Address])] =
        bs.utf8String match {
          case s if s.startsWith("!") => s drop 1 split ";" match {
            case Array(n, addr, seed, "none", ut) => Some(n, addr, Set.empty[String], ut.toLong, Set.empty[String], convertSeed(seed))
            case Array(n, addr, seed, "none", ut, _) => Some(n, addr, Set.empty[String], ut.toLong, Set.empty[String], convertSeed(seed))
            case Array(n, addr, seed, m, ut, rs) => Some(n, addr, m.split(",").toSet, ut.toLong, rs.split(",").toSet, convertSeed(seed))
            case Array(n, addr, seed, m, ut) => Some(n, addr, m.split(",").toSet, ut.toLong, Set.empty[String], convertSeed(seed))
            case _ => None
          }
          case _ => None
        }
    }

  }

  case class Endpoint(uri: String, host: String, port: Int, addr: InetSocketAddress)

  case class ManagerStateData(
                               system: ActorSystem,
                               socket: Option[ActorRef] = None,
                               endpoints: List[Endpoint] = List.empty,
                               responses: Map[String, UdpMessages.Response] = Map.empty,
                               currentLeader: Option[Address] = None,
                               currentMembers: Map[Address, Set[String]] = Map.empty,
                               lastDiscoveredClusters: Set[ReachableCluster] = Set.empty,
                               lastSeeds: Set[Address] = Set.empty,
                               blockedPorts: Set[Int] = Set.empty
                             ) {

    def isBlocked(addr: InetSocketAddress) = blockedPorts.contains(addr.getPort)

    lazy val haveResponsesFromAll: Boolean = endpoints.forall { ep => responses.contains(ep.uri) }

    lazy val combinedRoles = currentMembers.values.flatten.toSet

    lazy val seeds = responses.values.collect {
      case r if r.seed.isDefined => r.seed.get
    }.toSet

    def otherReachableClusters = responses.values.collect {
      case r if isAnotherCluster(r) => ReachableCluster(r.members.map(AddressFromURIString(_)), r.roles, None)
    }.toSet

    lazy val discoveredClustersSetChanged: Boolean = otherReachableClusters != lastDiscoveredClusters

    lazy val discoveredSeedsSetChanged: Boolean = seeds != lastSeeds

    lazy val ourCluster: Option[ReachableCluster] = currentLeader.map { leader =>
      ReachableCluster(currentMembers.keys.toSet, combinedRoles, Some(system.uptime))
    }

    private def isAnotherCluster(r: UdpMessages.Response) = r.members.nonEmpty && !r.members.exists { m => currentMembers.contains(AddressFromURIString(m)) }
  }


  object Messages {

    case class BlockCommunicationWith(host: String, port: Int)

    case class UnblockCommunicationWith(host: String, port: Int)

  }

  private object InternalMessages {

    case object Start

    case object DiscoveryTimeout

    case object PerformHandshake

    case object CheckState

    case class SendResponseTo(uri: String, addr: InetSocketAddress)

  }

  private object States {

    case object Initial extends ActorState

    case object InitialDiscovery extends ActorState

    case object ContinuousDiscovery extends ActorState

  }


}


class UdpClusterManagerActor extends StatefulActor[ManagerStateData] with UdpClusterManagerActorEvt {

  import InternalMessages._
  import Messages._
  import States._
  import UdpMessages._

  implicit val sys = context.system

  implicit val cluster = Cluster(sys)
  private lazy val selfAddress = cluster.selfAddress

  private val seedNodes = nodeCfg.asStringList("node.cluster.discovery.seed-nodes")
  private val seedRoles = nodeCfg.asStringList("node.cluster.discovery.seed-roles")
  private lazy val mySeed = if (seedNodes.exists(selfAddress.toString.contains) || seedRoles.exists(cluster.selfRoles.contains)) Some(cluster.selfAddress) else None

  val udpEndpointHost = config.asString("node.cluster.discovery.udp-endpoint.host", "localhost")
  val udpEndpointPort = config.asInt("node.cluster.discovery.udp-endpoint.port", 0)
  val initialDiscoveryTimeout = nodeCfg.asFiniteDuration("node.cluster.discovery.pre-discovery-timeout", 10 seconds)

  val endpoints = config.asStringList("node.cluster.discovery.udp-contacts").map { s =>
    s.split(":") match {
      case Array(hostOnly) => Endpoint(hostOnly + ":" + udpEndpointPort, hostOnly, udpEndpointPort, new InetSocketAddress(hostOnly, udpEndpointPort))
      case Array(host, port) => Endpoint(host + ":" + port, host, port.toInt, new InetSocketAddress(host, port.toInt))
      case _ => throw new RuntimeException(s"Invalid udp-contacts entry: $s")
    }
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
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


  startWith(Initial, ManagerStateData(endpoints = endpoints, system = context.system))


  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(udpEndpointHost, udpEndpointPort))

  when(Initial) {
    case Event(Udp.Bound(local), state: ManagerStateData) =>
      UdpBound('local -> local)
      transitionTo(InitialDiscovery) using state.copy(socket = Some(sender()))
  }

  when(InitialDiscovery) {
    case Event(CheckState, state: ManagerStateData) => if (state.haveResponsesFromAll) transitionTo(ContinuousDiscovery) else stay()
    case Event(DiscoveryTimeout, state) => transitionTo(ContinuousDiscovery)
  }

  when(ContinuousDiscovery) {
    case Event(CheckState, state: ManagerStateData) =>
      var newState = state

      if (state.discoveredClustersSetChanged) {
        val discoveredSet = state.otherReachableClusters
        context.parent ! ReachableClusters(state.ourCluster, discoveredSet)
        newState = newState.copy(lastDiscoveredClusters = discoveredSet)
      }

      if (state.discoveredSeedsSetChanged) {
        val seedsSet = state.seeds
        context.parent ! ReachableNodes(seedsSet)
        newState = newState.copy(lastSeeds = seedsSet)
      }

      stay using newState
  }

  otherwise {
    case Event(PerformHandshake, state: ManagerStateData) =>
      state.endpoints.foreach { ep =>
        if (!state.isBlocked(ep.addr)) state.socket.foreach(_ ! Udp.Send(Request(ep.uri).toByteString, ep.addr))
      }
      stay()
    case Event(Udp.Received(Request(uri), senderAddress), state: ManagerStateData) =>
      if (!state.isBlocked(senderAddress)) self ! SendResponseTo(uri, senderAddress)
      stay()
    case Event(Udp.Received(Response(uri, addr, members, uptime, roles, s), senderAddress), state: ManagerStateData) =>
      if (!state.isBlocked(senderAddress)) {
        self ! CheckState
        stay using state.copy(responses = state.responses + (uri -> Response(uri, addr, members, uptime, roles, s)))
      } else stay()

    case Event(SendResponseTo(uri, addr), state: ManagerStateData) =>
      if (!state.isBlocked(addr)) {
        state.socket.foreach(_ ! Udp.Send(Response(uri, selfAddress.toString, state.currentMembers.keys.map(_.toString).toSet, context.system.uptime, state.combinedRoles, mySeed).toByteString, addr))
      }
      stay()
    case Event(LeaderChanged(leader), state: ManagerStateData) =>
      NewLeaderElected('leader -> leader)
      self ! CheckState
      stay using state.copy(currentLeader = leader)
    case Event(MemberExited(member), state: ManagerStateData) =>
      NodeExited('addr -> member.address.toString)
      self ! CheckState
      stay using state.copy(responses = state.responses.filter(_._2.address != member.address.toString), currentMembers = state.currentMembers - member.address)
    case Event(MemberRemoved(member, _), state: ManagerStateData) =>
      NodeRemoved('addr -> member.address.toString)
      self ! CheckState
      stay using state.copy(responses = state.responses.filter(_._2.address != member.address.toString), currentMembers = state.currentMembers - member.address)
    case Event(UnreachableMember(member), state: ManagerStateData) =>
      NodeUnreachable('addr -> member.address.toString)
      self ! CheckState
      stay using state.copy(responses = state.responses.filter(_._2.address != member.address.toString), currentMembers = state.currentMembers + (member.address -> member.roles))
    case Event(ReachableMember(member), state: ManagerStateData) =>
      NodeReachable('addr -> member.address.toString)
      self ! CheckState
      stay using state.copy(currentMembers = state.currentMembers + (member.address -> member.roles))
    case Event(MemberUp(member), state: ManagerStateData) =>
      NodeUp('addr -> member.address.toString)
      self ! CheckState
      stay using state.copy(currentMembers = state.currentMembers + (member.address -> member.roles))
    case Event(BlockCommunicationWith(host, port), state: ManagerStateData) =>
      stay using state.copy(blockedPorts = state.blockedPorts + port)
    case Event(UnblockCommunicationWith(host, port), state: ManagerStateData) =>
      self ! CheckState
      self ! PerformHandshake
      stay using state.copy(blockedPorts = state.blockedPorts - port)
  }

  onTransition {
    case _ -> InitialDiscovery =>
      setTimer("timeout", DiscoveryTimeout, initialDiscoveryTimeout, repeat = false)
      setTimer("handshake", PerformHandshake, 2 seconds, repeat = true)
      setTimer("checkState", CheckState, 200 millis, repeat = true)
      self ! CheckState
      self ! PerformHandshake
    case _ -> ContinuousDiscovery =>
      cancelTimer("timeout")
      setTimer("handshake", PerformHandshake, 5 seconds, repeat = true)
      setTimer("checkState", CheckState, 1 seconds, repeat = true)
      self ! CheckState
      self ! PerformHandshake

  }


}
