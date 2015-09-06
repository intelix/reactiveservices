package rs.core.services

import scala.language.implicitConversions


case class StreamId(id: String, subId: Option[Any] = None)

object StreamId {
  implicit def toStreamId(id: String): StreamId = StreamId(id, None)
  implicit def toStreamId(id: (String, Any)): StreamId = StreamId(id._1, Some(id._2))
}



