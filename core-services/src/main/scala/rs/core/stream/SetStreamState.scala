package rs.core.stream

import java.util

import rs.core.Subject
import rs.core.javaapi.JServiceCell
import rs.core.services.ServiceCell
import rs.core.services.endpoint.StreamConsumer
import rs.core.services.internal.StreamId
import rs.core.stream.SetStreamState._

import scala.language.implicitConversions

object SetStreamState {

  sealed trait SetOp

  case class Add(el: String) extends SetOp

  case class Remove(el: String) extends SetOp

  case class SetSpecs(allowPartialUpdates: Boolean = true)


  private def calculateDiff(fromSet: Set[String], toSet: Set[String]): Seq[SetOp] = {
    (fromSet diff toSet).map(Remove).toSeq ++ (toSet diff fromSet).map(Add).toSeq
  }

}

case class SetStreamState(seed: Int, seq: Int, set: Set[String], specs: SetSpecs) extends StreamState with StreamStateTransition {
  override def transitionFrom(olderState: Option[StreamState]): Option[StreamStateTransition] = olderState match {
    case Some(SetStreamState(otherSeed, otherSeq, otherSet, _)) if specs.allowPartialUpdates && otherSeed == seed =>
      val diffSet = SetStreamState.calculateDiff(otherSet, set)
      if (diffSet.size < set.size)
        Some(SetStreamTransitionPartial(seed, otherSeq, seq, diffSet))
      else
        Some(this)
    case _ => Some(this)
  }

  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = Some(this)

  override def applicableTo(state: Option[StreamState]): Boolean = true
}

case class SetStreamTransitionPartial(seed: Int, seq: Int, seq2: Int, list: Seq[SetOp]) extends StreamStateTransition {
  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = state match {
    case Some(st@SetStreamState(otherSeed, otherSeq, set, _)) if otherSeed == seed && otherSeq == seq =>
      val newSet = list.foldLeft[Set[String]](set) {
        case (s, Add(e)) => s + e
        case (s, Remove(e)) => s - e
      }
      Some(st.copy(seq = seq + 1, set = newSet))
    case _ => None
  }

  override def applicableTo(state: Option[StreamState]): Boolean = state match {
    case Some(SetStreamState(otherSeed, otherSeq, _, _)) => otherSeed == seed && otherSeq == seq
    case _ => false
  }
}

trait SetStreamConsumer extends StreamConsumer {

  type SetStreamConsumer = PartialFunction[(Subject, Set[String]), Unit]

  onStreamUpdate {
    case (s, x: SetStreamState) => composedFunction(s, x.set)
  }

  final def onSetRecord(f: SetStreamConsumer) =
    composedFunction = f orElse composedFunction

  private var composedFunction: SetStreamConsumer = {
    case _ =>
  }

}

trait JSetStreamPublisher extends SetStreamPublisher {
  self: JServiceCell =>

  def streamSetSnapshot(s: StreamId, l: util.Set[String], allowPartialUpdates: Boolean): Unit = {
    implicit val setSpecs = SetSpecs(allowPartialUpdates)
    s !% l.toArray.toSet.asInstanceOf[Set[String]]
  }

  def streamSetSnapshot(s: String, l: util.Set[String], allowPartialUpdates: Boolean): Unit = streamSetSnapshot(StreamId(s), l, allowPartialUpdates)

  def streamSetAdd(s: StreamId, v: String): Unit = s !%+ v

  def streamSetRemove(s: StreamId, v: String): Unit = s !%- v

  def streamSetAdd(s: String, v: String): Unit = s !%+ v

  def streamSetRemove(s: String, v: String): Unit = s !%- v
}


trait SetStreamPublisher {
  self: ServiceCell =>

  implicit def toSetPublisher(v: String): SetPublisher = SetPublisher(v)

  implicit def toSetPublisher(v: StreamId): SetPublisher = SetPublisher(v)

  def ?%(s: StreamId): Option[SetStreamState] = currentStreamState(s) flatMap {
    case s: SetStreamState => Some(s)
    case _ => None
  }

  case class SetPublisher(s: StreamId) {

    def !%(l: => Set[String])(implicit specs: SetSpecs): Unit = ?%(s) match {
      case Some(x) => onStateTransition(s, SetStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, l, specs))
      case None => onStateTransition(s, SetStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, l, specs))
    }

    def streamSetSnapshot(l: => Set[String])(implicit specs: SetSpecs): Unit = !%(l)

    def !%+(v: => String): Unit = ?%(s) match {
      case Some(x) => onStateTransition(s, SetStreamTransitionPartial(x.seed, x.seq, x.seq + 1, List(Add(v))))
      case None => logger.error("!>>>> OH!")
    }

    def streamSetAdd(v: => String): Unit = !%+(v)

    def !%-(v: => String): Unit = ?%(s) match {
      case Some(x) => onStateTransition(s, SetStreamTransitionPartial(x.seed, x.seq, x.seq + 1, List(Remove(v))))
      case None => logger.error("!>>>> OH!")
    }

    def streamSetRemove(v: => String): Unit = !%-(v)

  }

}
