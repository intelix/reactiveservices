package rs.services.tcpserver

import akka.actor.ActorSystem
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.stream.scaladsl.{Source, Tcp}

import scala.concurrent.Future

object TCPListener {

  def connections(implicit sys: ActorSystem): Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind("0.0.0.0", 8888)

}
