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
package au.com.intelix.rs.websocket

import akka.NotUsed
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, Sink}
import akka.stream.{BidiShape, OverflowStrategy}
import akka.util.ByteString
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.rs.core.config.ServiceConfig

import scala.concurrent.Future


private[websocket] object WebsocketBinaryFrameFolder {

  def buildStage(sessionId: String, componentId: String)(implicit mat: akka.stream.Materializer, sCfg: ServiceConfig): BidiFlow[Message, ByteString, ByteString, Message, NotUsed] =
    BidiFlow.fromGraph(GraphDSL.create() { b =>
    val top = Flow[Message]
      .buffer(2, OverflowStrategy.backpressure)
      .filter {
        case t: BinaryMessage => true
        case t => false
      }.mapAsync[ByteString](sCfg.asInt("packet-folding-parallelism", 2)) {
        case BinaryMessage.Strict(bs) => Future.successful(bs)
        case t: BinaryMessage =>
          val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
          t.dataStream.runWith(sink)
        case _ => Future.successful(ByteString.empty)
    }
    val bottom = Flow[ByteString]
      .buffer(2, OverflowStrategy.backpressure)
      .map { b => BinaryMessage(b) }
    BidiShape.fromFlows(b.add(top), b.add(bottom))
  })

}
