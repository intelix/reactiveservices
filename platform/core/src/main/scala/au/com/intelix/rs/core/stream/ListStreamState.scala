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
package au.com.intelix.rs.core.stream

import java.util

import au.com.intelix.rs.core.Subject
import au.com.intelix.rs.core.javaapi.JServiceActor
import au.com.intelix.rs.core.services.endpoint.StreamConsumer
import au.com.intelix.rs.core.services.{BaseServiceActor, SimpleStreamId, StreamId}
import au.com.intelix.rs.core.stream.ListStreamState._

import scala.language.implicitConversions


object ListStreamState {

  def applyOpForSpecs(list: List[Any], sp: ListSpecs, op: Op): Option[List[Any]] =
    applyOp(list, op) map {
      case l if l.size <= sp.max => l
      case l => sp.evictionStrategy match {
        case RejectAdd => list
        case FromHead => l.tail
        case FromTail => l.take(sp.max)
      }
    }

  private def applyOp(list: List[Any], op: Op): Option[List[Any]] = op match {
    case Add(0, v) => Some(v +: list)
    case Add(-1, v) => Some(list :+ v)
    case Add(i, v) if i < -1 && list.size + i >= 0 =>
      Some((list.take(list.size + i) :+ v) ++ list.drop(list.size + i))
    case Add(i, v) if i > 0 && list.size > i =>
      Some((list.take(i) :+ v) ++ list.drop(i))
    case Remove(0) if list.nonEmpty => Some(list.tail)
    case Remove(-1) if list.nonEmpty => Some(list.take(list.size - 1))
    case Remove(i) if i < -1 && list.size + i >= 0 =>
      Some(list.take(list.size + i) ++ list.drop(list.size + i + 1))
    case Remove(i) if i > 0 && list.size > i =>
      Some(list.take(i) ++ list.drop(i + 1))
    case Replace(0, v) if list.nonEmpty => Some(v +: list.tail)
    case Replace(-1, v) if list.nonEmpty => Some(list.take(list.size - 1) :+ v)
    case Replace(i, v) if i < -1 && list.size + i >= 0 =>
      Some((list.take(list.size + i) :+ v) ++ list.drop(list.size + i + 1))
    case Replace(i, v) if i > 0 && list.size > i =>
      Some((list.take(i) :+ v) ++ list.drop(i + 1))
    case _ => None
  }

  sealed trait EvictionStrategy

  sealed trait Op

  case class Add(pos: Int, v: Any) extends Op

  case class Replace(pos: Int, v: Any) extends Op

  case class Remove(pos: Int) extends Op

  case class ListSpecs(max: Int, evictionStrategy: EvictionStrategy)

  case object RejectAdd extends EvictionStrategy {
    def instance = RejectAdd
  }

  case object FromHead extends EvictionStrategy {
    def instance = FromHead
  }

  case object FromTail extends EvictionStrategy {
    def instance = FromTail
  }


}

case class ListStreamState(seed: Int, seq: Int, list: List[Any], specs: ListSpecs, updatesCache: List[Op]) extends StreamState with StreamStateTransition {
  override def transitionFrom(olderState: Option[StreamState]): Option[StreamStateTransition] = olderState match {
    case Some(ListStreamState(otherSeed, otherSeq, otherList, _, _)) if otherSeed == seed && otherSeq == seq => None
    case Some(ListStreamState(otherSeed, otherSeq, otherList, _, _)) if otherSeed == seed && otherSeq < seq && seq - otherSeq < updatesCache.length =>
      Some(ListStreamStateTransitionPartial(seed, otherSeq, seq, updatesCache.takeRight(seq - otherSeq)))
    case _ => Some(this.copy(updatesCache = List.empty))
  }

  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = Some(this)

  override def applicableTo(state: Option[StreamState]): Boolean = true
}

case class ListStreamStateTransitionPartial(seed: Int, seq: Int, seq2: Int, list: List[Op]) extends StreamStateTransition {
  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = state match {
    case Some(ListStreamState(see, s, l, sp, uc)) if seq == s =>
      list.foldLeft[Option[List[Any]]](Some(l)) {
        case (Some(result), op) => ListStreamState.applyOpForSpecs(result, sp, op)
        case (None, op) =>
          None
      } map (ListStreamState(see, seq + list.length, _, sp, (uc ++ list).takeRight(sp.max)))
    case x =>
      None
  }

  override def applicableTo(state: Option[StreamState]): Boolean = state match {
    case Some(ListStreamState(see, s, l, sp, uc)) => seq == s
    case _ => false
  }
}


trait JListStreamPublisher extends ListStreamPublisher {
  self: JServiceActor =>

  def listEvictionFromHead = FromHead

  def listEvictionFromTail = FromTail

  def listEvictionReject = RejectAdd

  def streamListSnapshot(s: StreamId, l: util.List[Any], maxEntries: Int, evictionStrategy: EvictionStrategy): Unit =
    streamListSnapshot(s, l.toArray.toList, maxEntries, evictionStrategy)

  def streamListSnapshot(s: String, l: util.List[Any], maxEntries: Int, evictionStrategy: EvictionStrategy): Unit =
    streamListSnapshot(s, l.toArray.toList, maxEntries, evictionStrategy)

  def streamListSnapshot(s: String, l: List[Any], maxEntries: Int, evictionStrategy: EvictionStrategy): Unit =
    streamListSnapshot(SimpleStreamId(s), l, maxEntries, evictionStrategy)

  def streamListSnapshot(s: StreamId, l: List[Any], maxEntries: Int, evictionStrategy: EvictionStrategy): Unit = {
    implicit val specs = ListSpecs(maxEntries, evictionStrategy)
    s !:! l.toArray.toList
  }

  def streamListAdd(s: String, pos: Int, v: Any): Unit = s !:+(pos, v)

  def streamListRemove(s: String, pos: Int): Unit = s !:- pos

  def streamListReplace(s: String, pos: Int, v: Any): Unit = s !:*(pos, v)

  def streamListRemoveValue(s: String, v: Any): Unit = s !:-? v

  def streamListReplaceValue(s: String, v: Any, newV: Any): Unit = s !:*?(v, newV)

  def streamListAdd(s: StreamId, pos: Int, v: Any): Unit = s !:+(pos, v)

  def streamListRemove(s: StreamId, pos: Int): Unit = s !:- pos

  def streamListReplace(s: StreamId, pos: Int, v: Any): Unit = s !:*(pos, v)

  def streamListRemoveValue(s: StreamId, v: Any): Unit = s !:-? v

  def streamListReplaceValue(s: StreamId, v: Any, newV: Any): Unit = s !:*?(v, newV)

}


trait ListStreamPublisher {
  self: BaseServiceActor =>

  implicit def toListPublisher(v: String): ListPublisher = ListPublisher(v)

  implicit def toListPublisher(v: StreamId): ListPublisher = ListPublisher(v)

  def ?:(s: StreamId): Option[ListStreamState] = currentStreamState(s) flatMap {
    case s: ListStreamState => Some(s)
    case _ => None
  }


  case class ListPublisher(s: StreamId) {

    def streamListSnapshot(l: => List[Any])(implicit specs: ListSpecs): Unit = !:!(l)

    def !:!(l: => List[Any])(implicit specs: ListSpecs): Unit = ?:(s) match {
      case Some(x) =>
        val list = l
        if (list != x.list) performStateTransition(s, ListStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, list, specs, List.empty))
      case None => performStateTransition(s, ListStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, l, specs, List.empty))
      case _ =>
    }

    def streamListAdd(pos: Int, v: => Any): Unit = !:+(pos, v)

    def !:+(pos: Int, v: => Any): Unit = ?:(s) match {
      case Some(x) => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Add(pos, v))))
      case None =>
    }

    def streamListRemove(pos: Int): Unit = !:-(pos)

    def !:-(pos: Int): Unit = ?:(s) match {
      case Some(x) => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Remove(pos))))
      case None =>
    }

    def streamListRemoveValue(pos: Int, v: => Any): Unit = !:-?(v)

    def !:-?(v: => Any): Unit = ?:(s) match {
      case Some(x) => locateValue(v, x) foreach { pos => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Remove(pos)))) }
      case None =>
    }

    def streamListReplace(pos: Int, v: => Any): Unit = !:*(pos, v)

    def !:*(pos: Int, v: => Any): Unit = ?:(s) match {
      case Some(x) => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Replace(pos, v))))
      case None =>
    }

    def streamListReplaceValue(v: => Any, newV: => Any): Unit = !:*?(v, newV)

    def !:*?(v: => Any, newV: => Any): Unit = ?:(s) match {
      case Some(x) => locateValue(v, x) foreach { pos => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Replace(pos, newV)))) }
      case None =>
    }

    private def locateValue(v: Any, t: ListStreamState): Option[Int] =
      t.list.zipWithIndex.find(_._1 == v).map(_._2)


  }

}


trait ListStreamConsumer extends StreamConsumer {

  type ListStreamConsumer = PartialFunction[(Subject, List[Any]), Unit]

  onStreamUpdate {
    case (s, x: ListStreamState) => composedFunction((s, x.list))
  }
  private var composedFunction: ListStreamConsumer = {
    case _ =>
  }

  final def onListRecord(f: ListStreamConsumer) =
    composedFunction = f orElse composedFunction

}
