package rs.core.serializer

import akka.util.ByteStringBuilder


object RSThreadSafeCodec {

  val B1 = 0xF
  val B2 = (B1 << 8) | 0xFF
  val B3 = (B2 << 8) | 0xFF
  val B4 = (B3 << 8) | 0xFF

  def putLong(l: Long, b: ByteStringBuilder) = {
    val sig = if (l > 0) 0 else 1
    val pos = if (l > 0) l else -l
    val bytes = l match {
      case x if x <= B1 => 0
      case x if x <= B1 => 1
      case x if x <= B1 => 1
    }

  }

}

private[serializer] class RSThreadSafeCodec {
  import RSThreadSafeCodec._

//  def encode(input: AnyRef): Array[Byte] = ???
//
//  def decode(input: Array[Byte]): AnyRef = ???
}
