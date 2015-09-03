package rs.service.websocket

import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.stream.BidiShape
import akka.stream.scaladsl.{BidiFlow, Flow, Sink}
import akka.util.ByteString

import scala.concurrent.Future


private[websocket] object WebsocketBinaryFrameFolder {

  def buildStage()(implicit mat: akka.stream.Materializer): BidiFlow[Message, ByteString, ByteString, Message, Unit] = BidiFlow() { b =>
    val top = Flow[Message]
      .filter {
        case t: BinaryMessage => true
        case t =>
         println("!>>>> Message filtered: " + t)
         false
      }.mapAsync[ByteString](50) {
        case BinaryMessage.Strict(bs) => Future.successful(bs)
        case t: BinaryMessage =>
          val sink = Sink.fold[ByteString, ByteString](ByteString.empty) {
            case (bx, n) =>
              println(s"!>>> Folding next, ${n.size}")
              bx ++ n
          }
          t.dataStream.runWith(sink)
        case _ => Future.successful(ByteString.empty)
    }
    val bottom = Flow[ByteString].map { b => BinaryMessage(b) }
    BidiShape(b.add(top), b.add(bottom))
  }

}
