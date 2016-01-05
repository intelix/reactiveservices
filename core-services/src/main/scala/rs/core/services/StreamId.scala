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
package rs.core.services

import rs.core.Ser

import scala.language.implicitConversions

trait StreamId extends Ser

case class SimpleStreamId(id: String) extends StreamId {
  override lazy val toString: String = id
}
case class CompoundStreamId[T](id: String, v: T) extends StreamId {
  override lazy val toString: String = id + "#" + v
}

object StreamId {
  implicit def toStreamId(id: String): StreamId = SimpleStreamId(id)

  implicit def toStreamId(id: (String, Any)): StreamId = CompoundStreamId(id._1, id._2)
}

abstract class CompoundStreamIdTemplate[T](id: String) {
  def apply(v: T) = CompoundStreamId(id, v)
  def unapply(s: StreamId): Option[T] = s match {
    case CompoundStreamId(i, v) if i == id  => Some(v.asInstanceOf[T])
    case _ => None
  }
}

abstract class SimpleStreamIdTemplate(id: String) {
  def apply() = SimpleStreamId(id)
  def unapply(s: StreamId): Boolean = s match {
    case SimpleStreamId(i) => i == id
    case _ => false
  }
}

