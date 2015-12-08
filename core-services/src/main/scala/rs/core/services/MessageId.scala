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
package rs.core.services

import rs.core.tools.UUIDTools

sealed trait MessageIdOrder

case object Older extends MessageIdOrder

case object Same extends MessageIdOrder

case object Newer extends MessageIdOrder

case object Unknown extends MessageIdOrder

trait MessageId {
  def compareWith(m: MessageId): MessageIdOrder
}

class SequentialMessageIdGenerator {

  val seed = UUIDTools.generateShortUUID
  var counter = 0L

  def next() = {
    counter += 1
    SequentialMessageId(seed, counter)
  }

}

case class LongMessageId(id: Long) extends MessageId {
  override def compareWith(m: MessageId): MessageIdOrder = if (m == this) Same else Unknown

  override def toString: String = id.toString
}

case class RandomStringMessageId(id: String = UUIDTools.generateShortUUID) extends MessageId {
  override def compareWith(m: MessageId): MessageIdOrder = if (m == this) Same else Unknown

  override def toString: String = id
}

case class SequentialMessageId(seed: String, sequence: Long) extends MessageId {
  override def compareWith(m: MessageId): MessageIdOrder = m match {
    case SequentialMessageId(otherSeed, otherSeq) if otherSeed == seed && otherSeq == sequence => Same
    case SequentialMessageId(otherSeed, otherSeq) if otherSeed == seed && otherSeq > sequence => Older
    case SequentialMessageId(otherSeed, otherSeq) if otherSeed == seed && otherSeq < sequence => Newer
    case _ => Unknown
  }

  override def toString: String = seed + ":" + sequence
}
