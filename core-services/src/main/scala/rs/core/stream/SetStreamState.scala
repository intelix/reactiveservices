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
package rs.core.stream

import java.util

import rs.core.Subject
import rs.core.javaapi.JServiceActor
import rs.core.services.endpoint.StreamConsumer
import rs.core.services.{SimpleStreamId, BaseServiceActor, StreamId}
import rs.core.stream.SetStreamState._

import scala.language.implicitConversions

object SetStreamState {

  private def calculateDiff(fromSet: Set[Any], toSet: Set[Any]): Seq[SetOp] = {
    (fromSet diff toSet).map(Remove).toSeq ++ (toSet diff fromSet).map(Add).toSeq
  }

  sealed trait SetOp

  case class Add(el: Any) extends SetOp

  case class Remove(el: Any) extends SetOp

  case class SetSpecs(allowPartialUpdates: Boolean = true)

}

case class SetStreamState(seed: Int, seq: Int, set: Set[Any], specs: SetSpecs) extends StreamState with StreamStateTransition {
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
      val newSet = list.foldLeft[Set[Any]](set.toSet) {
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

  type SetStreamConsumer = PartialFunction[(Subject, Set[Any]), Unit]

  onStreamUpdate {
    case (s, x: SetStreamState) => composedFunction((s, x.set))
  }
  private var composedFunction: SetStreamConsumer = {
    case _ =>
  }

  final def onSetRecord(f: SetStreamConsumer) =
    composedFunction = f orElse composedFunction

}

trait JSetStreamPublisher extends SetStreamPublisher {
  self: JServiceActor =>

  def streamSetSnapshot(s: String, l: util.Set[Any], allowPartialUpdates: Boolean): Unit = streamSetSnapshot(SimpleStreamId(s), l, allowPartialUpdates)

  def streamSetSnapshot(s: StreamId, l: util.Set[Any], allowPartialUpdates: Boolean): Unit = {
    implicit val setSpecs = SetSpecs(allowPartialUpdates)
    s !% l.toArray.toSet
  }

  def streamSetAdd(s: StreamId, v: Any): Unit = s !%+ v

  def streamSetRemove(s: StreamId, v: Any): Unit = s !%- v

  def streamSetAdd(s: String, v: Any): Unit = s !%+ v

  def streamSetRemove(s: String, v: Any): Unit = s !%- v


}


trait SetStreamPublisher {
  self: BaseServiceActor =>

  implicit def toSetPublisher(v: String): SetPublisher = SetPublisher(v)

  implicit def toSetPublisher(v: StreamId): SetPublisher = SetPublisher(v)

  def ?%(s: StreamId): Option[SetStreamState] = currentStreamState(s) flatMap {
    case s: SetStreamState => Some(s)
    case _ => None
  }

  case class SetPublisher(s: StreamId) {

    def streamSetSnapshot(l: => Set[Any])(implicit specs: SetSpecs): Unit = !%(l)

    def !%[T](l: => Set[T])(implicit specs: SetSpecs): Unit = ?%(s) match {
      case Some(x) =>
        val set = l
        if (set != x.set) performStateTransition(s, SetStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, l.asInstanceOf[Set[Any]], specs))
      case None => performStateTransition(s, SetStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, l.asInstanceOf[Set[Any]], specs))
    }

    def streamSetAdd(v: => Any): Unit = !%+(v)

    def !%+(v: => Any): Unit = ?%(s) match {
      case Some(x) => performStateTransition(s, SetStreamTransitionPartial(x.seed, x.seq, x.seq + 1, Seq(Add(v))))
      case None =>
    }

    def streamSetRemove(v: => Any): Unit = !%-(v)

    def !%-(v: => Any): Unit = ?%(s) match {
      case Some(x) => performStateTransition(s, SetStreamTransitionPartial(x.seed, x.seq, x.seq + 1, Seq(Remove(v))))
      case None =>
    }

  }

}
