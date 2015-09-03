package rs.core.services.endpoint.akkastreams

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Tcp, Source}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}

import scala.concurrent.Future

object TCPListener {

  def connections(implicit sys: ActorSystem): Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind("0.0.0.0", 8888)

}
