package rs.core.stream

import rs.core.Subject
import rs.core.services.ServiceCell
import rs.core.services.endpoint.StreamConsumer
import rs.core.services.internal.StreamId

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

    def !!(v: Any) = onStateTransition(s, CustomStreamState(v))

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