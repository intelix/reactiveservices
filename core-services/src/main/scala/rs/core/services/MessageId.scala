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

  var seed = System.nanoTime()
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

case class SequentialMessageId(seed: Long, sequence: Long) extends MessageId {
  override def compareWith(m: MessageId): MessageIdOrder = m match {
    case SequentialMessageId(otherSeed, otherSeq) if otherSeed == seed && otherSeq == sequence => Same
    case SequentialMessageId(otherSeed, otherSeq) if otherSeed == seed && otherSeq > sequence => Older
    case SequentialMessageId(otherSeed, otherSeq) if otherSeed == seed && otherSeq < sequence => Newer
    case _ => Unknown
  }

  override def toString: String = seed + ":" + sequence
}
