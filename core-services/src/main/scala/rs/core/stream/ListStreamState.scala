/*
 * Copyright 2014-15 Intelix Pty Ltd
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
import rs.core.javaapi.JServiceCell
import rs.core.services.{StreamId, ServiceCell}
import rs.core.services.endpoint.StreamConsumer
import rs.core.stream.ListStreamState._

import scala.language.implicitConversions


object ListStreamState {

  def applyOpForSpecs(list: List[String], sp: ListSpecs, op: Op): Option[List[String]] = sp.max match {
    case m if m > list.size => applyOp(list, op)
    case m => sp.evictionStrategy match {
      case RejectAdd => Some(list)
      case FromHead => applyOp(list, op).map(_.tail)
      case FromTail => applyOp(list, op).map(_.take(m))
    }
  }

  private def applyOp(list: List[String], op: Op): Option[List[String]] = op match {
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

  case class Add(pos: Int, v: String) extends Op

  case class Replace(pos: Int, v: String) extends Op

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

case class ListStreamState(seed: Int, seq: Int, list: List[String], specs: ListSpecs, updatesCache: List[Op]) extends StreamState with StreamStateTransition {
  override def transitionFrom(olderState: Option[StreamState]): Option[StreamStateTransition] = olderState match {
    case Some(ListStreamState(otherSeed, otherSeq, otherList, _, _)) if otherSeed == seed && otherSeq == seq => None
    case Some(ListStreamState(otherSeed, otherSeq, otherList, _, _)) if otherSeed == seed && otherSeq < seq && seq - otherSeq < updatesCache.length =>
      Some(ListStreamStateTransitionPartial(seed, otherSeq, seq, updatesCache.takeRight((seq - otherSeq).toInt)))
    case _ => Some(this.copy(updatesCache = List.empty))
  }

  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = Some(this)

  override def applicableTo(state: Option[StreamState]): Boolean = true
}

case class ListStreamStateTransitionPartial(seed: Int, seq: Int, seq2: Int, list: List[Op]) extends StreamStateTransition {
  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = state match {
    case Some(ListStreamState(see, s, l, sp, uc)) if seq == s =>
      list.foldLeft[Option[List[String]]](Some(l)) {
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
  self: JServiceCell =>

  def listEvictionFromHead = FromHead

  def listEvictionFromTail = FromTail

  def listEvictionReject = RejectAdd

  def streamListSnapshot(s: StreamId, l: util.List[String], maxEntries: Int, evictionStrategy: EvictionStrategy): Unit =
    streamListSnapshot(s, l.toArray.toList.asInstanceOf[List[String]], maxEntries, evictionStrategy)

  def streamListSnapshot(s: String, l: util.List[String], maxEntries: Int, evictionStrategy: EvictionStrategy): Unit =
    streamListSnapshot(s, l.toArray.toList.asInstanceOf[List[String]], maxEntries, evictionStrategy)

  def streamListSnapshot(s: String, l: List[String], maxEntries: Int, evictionStrategy: EvictionStrategy): Unit =
    streamListSnapshot(StreamId(s), l, maxEntries, evictionStrategy)

  def streamListSnapshot(s: StreamId, l: List[String], maxEntries: Int, evictionStrategy: EvictionStrategy): Unit = {
    implicit val specs = ListSpecs(maxEntries, evictionStrategy)
    s !:! l.toArray.toList.asInstanceOf[List[String]]
  }

  def streamListAdd(s: String, pos: Int, v: String): Unit = s !:+(pos, v)

  def streamListRemove(s: String, pos: Int): Unit = s !:- pos

  def streamListReplace(s: String, pos: Int, v: String): Unit = s !:*(pos, v)

  def streamListRemoveValue(s: String, v: String): Unit = s !:-? v

  def streamListReplaceValue(s: String, v: String, newV: String): Unit = s !:*?(v, newV)
}


trait ListStreamPublisher {
  self: ServiceCell =>

  implicit def toListPublisher(v: String): ListPublisher = ListPublisher(v)

  implicit def toListPublisher(v: StreamId): ListPublisher = ListPublisher(v)

  def ?:(s: StreamId): Option[ListStreamState] = currentStreamState(s) flatMap {
    case s: ListStreamState => Some(s)
    case _ => None
  }


  case class ListPublisher(s: StreamId) {

    def streamListSnapshot(l: => List[String])(implicit specs: ListSpecs): Unit = !:!(l)

    def !:!(l: => List[String])(implicit specs: ListSpecs): Unit = ?:(s) match {
      case Some(x) => performStateTransition(s, ListStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, l, specs, List.empty))
      case None => performStateTransition(s, ListStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, l, specs, List.empty))
    }

    def streamListAdd(pos: Int, v: => String): Unit = !:+(pos, v)

    def !:+(pos: Int, v: => String): Unit = ?:(s) match {
      case Some(x) => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Add(pos, v))))
      case None =>
    }

    def streamListRemove(pos: Int): Unit = !:-(pos)

    def !:-(pos: Int): Unit = ?:(s) match {
      case Some(x) => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Remove(pos))))
      case None =>
    }

    def streamListRemoveValue(pos: Int, v: => String): Unit = !:-?(v)

    def !:-?(v: => String): Unit = ?:(s) match {
      case Some(x) => locateValue(v, x) foreach { pos => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Remove(pos)))) }
      case None =>
    }

    def streamListReplace(pos: Int, v: => String): Unit = !:*(pos, v)

    def !:*(pos: Int, v: => String): Unit = ?:(s) match {
      case Some(x) => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Replace(pos, v))))
      case None =>
    }

    def streamListReplaceValue(v: => String, newV: => String): Unit = !:*?(v, newV)

    def !:*?(v: => String, newV: => String): Unit = ?:(s) match {
      case Some(x) => locateValue(v, x) foreach { pos => performStateTransition(s, ListStreamStateTransitionPartial(x.seed, x.seq, x.seq + 1, List(Replace(pos, newV)))) }
      case None =>
    }

    private def locateValue(v: String, t: ListStreamState): Option[Int] =
      t.list.zipWithIndex.find(_._1 == v).map(_._2)


  }

}


trait ListStreamConsumer extends StreamConsumer {

  type ListStreamConsumer = PartialFunction[(Subject, List[String]), Unit]

  onStreamUpdate {
    case (s, x: ListStreamState) => composedFunction(s, x.list)
  }
  private var composedFunction: ListStreamConsumer = {
    case _ =>
  }

  final def onListRecord(f: ListStreamConsumer) =
    composedFunction = f orElse composedFunction

}
