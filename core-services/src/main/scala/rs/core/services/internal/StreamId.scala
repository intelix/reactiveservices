package rs.core.services.internal

import scala.language.implicitConversions


case class StreamId(id: String)

object StreamId {
  implicit def toStreamId(id: String): StreamId = StreamId(id)
  implicit def toStreamId(id: (String, String)): StreamId = CompositeStreamId(id._1, id._2)
}

object CompositeStreamId {
  def apply(id: String, subId: String) = StreamId(id + "|" + subId)
  def unapply(x: StreamId): Option[(String, String)] = {
    val idx = x.id.indexOf('|')
    if (idx > -1)
      Some((x.id.substring(0, idx), x.id.substring(idx+1)))
    else
      None
  }

}


