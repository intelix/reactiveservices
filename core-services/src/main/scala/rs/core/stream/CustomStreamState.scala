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
package rs.core.stream

import rs.core.Subject
import rs.core.services.{StreamId, ServiceCell}
import rs.core.services.endpoint.StreamConsumer

import scala.language.implicitConversions

case class CustomStreamState(value: Any) extends StreamState with StreamStateTransition {

  lazy val Self = Some(this)

  override def transitionFrom(olderState: Option[StreamState]): Option[StreamStateTransition] = olderState match {
    case Some(CustomStreamState(x)) if x == value => None
    case _ => Self
  }

  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = Self

  override def applicableTo(state: Option[StreamState]): Boolean = true
}


trait CustomRecordPublisher {
  self: ServiceCell =>

  implicit def toCustomPublisher(v: String): CustomPublisher = CustomPublisher(v)

  implicit def toCustomPublisher(v: StreamId): CustomPublisher = CustomPublisher(v)

  case class CustomPublisher(s: StreamId) {

    def !!(v: Any) = performStateTransition(s, CustomStreamState(v))

    def anyRec = !! _
  }

}

trait CustomStreamConsumer extends StreamConsumer {

  type CustomStreamConsumer = PartialFunction[(Subject, Any), Unit]

  onStreamUpdate {
    case (s, CustomStreamState(value)) => composedFunction(s, value)
  }

  final def onCustomRecord(f: CustomStreamConsumer) =
    composedFunction = f orElse composedFunction

  private var composedFunction: CustomStreamConsumer = {
    case _ =>
  }

}