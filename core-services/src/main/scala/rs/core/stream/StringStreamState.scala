package rs.core.stream

import play.api.libs.json.{JsValue, Json}
import rs.core.Subject
import rs.core.javaapi.JServiceCell
import rs.core.services.{StreamId, ServiceCell}
import rs.core.services.endpoint.StreamConsumer

import scala.language.implicitConversions

case class StringStreamState(value: String) extends StreamState with StreamStateTransition {

  lazy val Self = Some(this)

  override def transitionFrom(olderState: Option[StreamState]): Option[StreamStateTransition] = olderState match {
    case Some(StringStreamState(x)) if x == value => None
    case _ => Self
  }

  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = Self

  override def applicableTo(state: Option[StreamState]): Boolean = true
}

trait JStringStreamPublisher {
  self: JServiceCell =>
  def streamString(stream: String, value: String) = performStateTransition(stream, StringStreamState(value))
  def streamString(stream: StreamId, value: String) = performStateTransition(stream, StringStreamState(value))
}


trait StringStreamPublisher {
  self: ServiceCell =>

  implicit def toString(v: JsValue): String = Json.stringify(v)

  implicit def toStringPublisher(v: String): StringPublisher = StringPublisher(v)

  implicit def toStringPublisher(v: StreamId): StringPublisher = StringPublisher(v)

  case class StringPublisher(s: StreamId) {

    def !~(v: String) = performStateTransition(s, StringStreamState(v))

    def strRec = !~ _
  }

}

trait StringStreamConsumer extends StreamConsumer {

  type StringStreamConsumer = PartialFunction[(Subject, String), Unit]

  onStreamUpdate {
    case (s, StringStreamState(value)) => composedFunction(s, value)
  }

  final def onStringRecord(f: StringStreamConsumer) =
    composedFunction = f orElse composedFunction

  private var composedFunction: StringStreamConsumer = {
    case _ =>
  }

}
