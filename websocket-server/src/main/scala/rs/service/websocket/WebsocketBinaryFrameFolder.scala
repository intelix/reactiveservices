/*
 * Copyright 2014-15 Intelix Pty Ltd
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
        case t => false
      }.mapAsync[ByteString](50) {
        case BinaryMessage.Strict(bs) => Future.successful(bs)
        case t: BinaryMessage =>
          val sink = Sink.fold[ByteString, ByteString](ByteString.empty) {
            case (bx, n) =>
              // TODO metrics here
              bx ++ n
          }
          t.dataStream.runWith(sink)
        case _ => Future.successful(ByteString.empty)
    }
    val bottom = Flow[ByteString].map { b => BinaryMessage(b) }
    BidiShape(b.add(top), b.add(bottom))
  }

}
