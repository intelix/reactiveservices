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
package rs.core.codec.binary

import akka.stream.BidiShape
import akka.stream.scaladsl.{BidiFlow, Flow}
import akka.util.ByteString

import scala.concurrent.duration._
import scala.language.postfixOps

object ByteStringAggregator {

  def buildStage(maxMessages: Int = 100, within: FiniteDuration = 200 millis): BidiFlow[ByteString, ByteString, ByteString, ByteString, Unit] = BidiFlow() { b =>

    val in = b.add(Flow[ByteString])
    val out = b.add(Flow[ByteString].groupedWithin(maxMessages, within).map {
      case Nil => ByteString.empty
      case bs :: Nil => bs
      case s => s.foldLeft(ByteString.empty)(_ ++ _)
    })

    BidiShape(in, out)
  }

}
