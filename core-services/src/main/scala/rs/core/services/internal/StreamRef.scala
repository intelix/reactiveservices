package rs.core.services.internal

import scala.language.implicitConversions


trait StreamRef

object StreamRef {
  val Nil = NilStreamRef

  implicit def toStreamRef(id: String): StreamRef = StringStreamRef(id)
}


case class StringStreamRef(id: String) extends StreamRef
case class StringStreamRef2(id: String, value: String) extends StreamRef
case object NilStreamRef extends StreamRef

