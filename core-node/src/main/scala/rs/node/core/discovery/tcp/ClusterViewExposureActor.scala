package rs.node.core.discovery.tcp

import java.net.InetSocketAddress
import java.nio.ByteOrder

import akka.actor.{ActorRef, Address, Props, Terminated}
import akka.cluster.Member
import akka.io.Tcp.{Bind, CommandFailed, ConnectionClosed}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import au.com.intelix.rs.core.actors.{ClusterAwareness, StatelessActor}
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.evt.EvtSource

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps


class ClusterViewExposureActor(endpointHost: String, endpointPort: Int) extends StatelessActor with ClusterAwareness with StrictLogging {
  override val evtSource: EvtSource = "ClusterViewExposure"

  import context.system

  val manager = IO(Tcp)


  var socket: Option[ActorRef] = None
  var connections: List[ActorRef] = List()

  self ! Internal.Bind

  onMessage {
    case Internal.Bind if socket.isEmpty =>
      manager ! Tcp.Bind(self, new InetSocketAddress(endpointHost, endpointPort))
    case Internal.Bind  => // ignore - already bound
    case CommandFailed(_:Bind) =>
      logger.error(s"!>>> Unable to bind to $endpointHost:$endpointPort")
      scheduleOnce(3 seconds, Internal.Bind)
    case Tcp.Bound(local) =>
      logger.info(s"!>>> Bound to $endpointHost:$endpointPort")
      socket = Some(sender())
    case Tcp.Connected(remote, local) =>
      logger.info(s"!>>> New incoming connection from $remote, connected at $local")
      val connection = sender()
      val manager = context.watch(context.actorOf(Props(classOf[ConnectionManager], connection), s"manager~${remote.getHostName}_${remote.getPort}"))
      connection ! Tcp.Register(manager)
      connections = connections :+ manager
      manager ! buildMessage()
    case Terminated(actor) =>
      connections = connections.filterNot(_ == actor)
  }

  onClusterAnyMemberSetChanged {
    case _ =>
      val msg = buildMessage()
      connections.foreach(_ ! msg)
  }

  private def buildMessage(): ByteString =
    TcpMessages.RemoteClusterView(reachableMembers.values.map(_.address.toString).toSet, clusterRoles).toByteString


  override def postStop(): Unit = {
    socket.foreach(_ ! Tcp.Unbind)
    super.postStop()
  }

  object Internal {
    case object Bind
  }

}


private class ConnectionManager(connection: ActorRef) extends StatelessActor with StrictLogging {
  override val evtSource: EvtSource = "ConnectionManager"

  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  onMessage {
    case t: ConnectionClosed =>
      logger.info(s"!>>> Connection terminated, ${t.getErrorCause}, aborted = ${t.isAborted}, peerclosed = ${t.isPeerClosed}")
      context.stop(self)
    case Tcp.Received(bs) =>
      logger.info(s"!>>>> Received something at the acceptor.... ${bs.utf8String}")
    // ignore...
    case m: ByteString =>
      logger.info("!>>> Sent " + m.utf8String + "  ->  " + connection)
      connection ! Tcp.Write(ByteString.createBuilder.putShort(m.size).result() ++ m)
    case m =>
      logger.info(s"!>>> Received unexpected message $m")
  }

}