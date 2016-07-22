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
package rs.core.stream

import play.api.libs.json.{JsValue, Json}
import rs.core.Subject
import rs.core.javaapi.JServiceActor
import rs.core.services.endpoint.StreamConsumer
import rs.core.services.{BaseServiceActor, StreamId}

import scala.language.implicitConversions

case class StringStreamState(value: String) extends StreamState with StreamStateTransition {

  override def transitionFrom(olderState: Option[StreamState]): Option[StreamStateTransition] = olderState match {
    case Some(StringStreamState(x)) if x == value => None
    case _ => Some(this)
  }

  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = Some(this)

  override def applicableTo(state: Option[StreamState]): Boolean = true
}

trait JStringStreamPublisher {
  self: JServiceActor =>
  def streamString(stream: String, value: String) = performStateTransition(stream, StringStreamState(value))

  def streamString(stream: StreamId, value: String) = performStateTransition(stream, StringStreamState(value))
}


trait StringStreamPublisher {
  self: BaseServiceActor =>

  implicit def toString(v: JsValue): String = Json.stringify(v)

  implicit def toStringPublisher(v: String): StringPublisher = StringPublisher(v)

  implicit def toStringPublisher(v: StreamId): StringPublisher = StringPublisher(v)


  def ?~(s: StreamId): Option[StringStreamState] = currentStreamState(s) flatMap {
    case s: StringStreamState => Some(s)
    case _ => None
  }

  case class StringPublisher(s: StreamId) {

    def !~(v: String) = ?~(s) match {
      case Some(x) =>
        if (x.value != v) performStateTransition(s, StringStreamState(v))
      case None => performStateTransition(s, StringStreamState(v))
      case _ =>
    }

    def strRec = !~ _
  }

}

trait StringStreamConsumer extends StreamConsumer {

  type StringStreamConsumer = PartialFunction[(Subject, String), Unit]

  onStreamUpdate {
    case (s, StringStreamState(value)) => composedFunction((s, value))
  }

  final def onStringRecord(f: StringStreamConsumer) =
    composedFunction = f orElse composedFunction

  private var composedFunction: StringStreamConsumer = {
    case _ =>
  }

}
