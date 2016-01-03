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
import rs.core.services.{BaseServiceActor, StreamId}
import rs.core.stream.DictionaryMapStreamState.{Dictionary, NoChange}

import scala.language.implicitConversions


object DictionaryMapStreamState {

  def calculateDiff(a: Array[Any], b: Array[Any]) = {
    var idx = 0
    val newArr = Array.ofDim[Any](b.length)
    while (idx < b.length) {
      newArr(idx) = b(idx) match {
        case x if x == a(idx) => NoChange
        case x => x
      }
      idx += 1
    }
    newArr
  }

  case class Dictionary(fields: Array[String]) {
    @transient lazy val asString: String = "Dictionary(" + fields.mkString(",") + ")"

    def locateIdx(field: String) = {
      var idx = 0
      while (idx < fields.length && fields(idx) != field) {
        idx += 1
      }
      if (fields(idx) == field) idx else -1
    }

    override def toString: String = asString
  }

  case object NoChange


  object Dictionary {
    def apply(s: String*): Dictionary = Dictionary(s.toArray)
  }


}


case class DictionaryMapStreamState(seed: Int, seq: Int, values: Array[Any], dict: Dictionary) extends StreamState with StreamStateTransition {

  @transient lazy val asString: String = "DictionaryMapStreamState(" + seed + "," + seq + "," + dict.fields.zip(values).map { case (a, b) => a + "=" + b }.mkString("{", ",", "}") + ")"

  def transitionTo(a: Array[Any]) = DictionaryMapStreamTransitionPartial(seed, seq, seq + 1, DictionaryMapStreamState.calculateDiff(values, a))

  override def transitionFrom(olderState: Option[StreamState]): Option[StreamStateTransition] = olderState match {
    case Some(st@DictionaryMapStreamState(otherSeed, otherSeq, a, _)) if otherSeed == seed && a.length == values.length =>
      Some(DictionaryMapStreamTransitionPartial(seed, otherSeq, seq, DictionaryMapStreamState.calculateDiff(a, values)))
    case _ => Some(this)
  }

  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = Some(this)

  override def applicableTo(state: Option[StreamState]): Boolean = true

  override def toString: String = asString
}


case class DictionaryMapStreamTransitionPartial(seed: Int, seq: Int, seq2: Int, diffs: Array[Any]) extends StreamStateTransition {
  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = state match {
    case Some(st@DictionaryMapStreamState(otherSeed, otherSeq, a, _)) if otherSeed == seed && otherSeq == seq && a.length == diffs.length =>
      Some(st.copy(seq = seq2, values = applyDiffsTo(a)))
    case _ => None
  }

  private def applyDiffsTo(values: Array[Any]) = {
    var idx = 0
    val newArr = Array.ofDim[Any](diffs.length)
    while (idx < diffs.length) {
      newArr(idx) = diffs(idx) match {
        case NoChange => values(idx)
        case x => x
      }
      idx += 1
    }
    newArr
  }

  override def applicableTo(state: Option[StreamState]): Boolean = state match {
    case Some(DictionaryMapStreamState(otherSeed, otherSeq, a, _)) if otherSeed == seed && otherSeq == seq && a.length == diffs.length => true
    case _ => false
  }

  override lazy val toString: String = s"""DictionaryMapStreamTransitionPartial($seed,$seq,$seq2,${diffs.mkString("[", ",", "]")})"""


}


class DictionaryMap(dict: Dictionary, values: Array[Any]) {
  def getOpt(key: String) = dict locateIdx key match {
    case -1 => None
    case i => Some(values(i))
  }

  def get[T](key: String, defaultValue: T) = dict locateIdx key match {
    case -1 => defaultValue
    case i => values(i).asInstanceOf[T]
  }

  lazy val asMap = dict.fields.zipWithIndex.map {
    case (v, k) => v -> values(k)
  }.toMap
}

trait DictionaryMapStreamConsumer extends StreamConsumer {

  type DictionaryMapStreamConsumer = PartialFunction[(Subject, DictionaryMap), Unit]
  private var composedFunction: DictionaryMapStreamConsumer = {
    case _ =>
  }

  onStreamUpdate {
    case (s, x: DictionaryMapStreamState) => composedFunction((s, toMap(x.values, x.dict)))
  }

  def toMap(values: Array[Any], dict: Dictionary): DictionaryMap = new DictionaryMap(dict, values)

  final def onDictMapRecord(f: DictionaryMapStreamConsumer) =
    composedFunction = f orElse composedFunction

}

trait JDictionaryMapStreamPublisher extends DictionaryMapStreamPublisher {
  self: JServiceActor =>

  import scala.collection.JavaConversions._

  def streamMapSnapshot(streamRef: String, values: Array[Any], dict: Dictionary) = streamRef.!#(values)(dict)

  def streamMapSnapshot(streamRef: String, values: util.Map[String, Any], dict: Dictionary) = streamRef.!#(values.toMap)(dict)

  def streamMapSnapshot(streamRef: StreamId, values: Array[Any], dict: Dictionary) = streamRef.!#(values)(dict)

  def streamMapSnapshot(streamRef: StreamId, values: util.Map[String, Any], dict: Dictionary) = streamRef.!#(values.toMap)(dict)

  def streamMapPut(streamRef: String, values: util.Map[String, Any], dict: Dictionary) = streamRef.!#+(values.toMap)(dict)

  def streamMapPut(streamRef: StreamId, values: util.Map[String, Any], dict: Dictionary) = streamRef.!#+(values.toMap)(dict)

}

trait DictionaryMapStreamPublisher {
  self: BaseServiceActor =>

  def ?#(s: StreamId): Option[DictionaryMapStreamState] = currentStreamState(s) flatMap {
    case s: DictionaryMapStreamState => Some(s)
    case _ => None
  }

  implicit def toDictionaryMapPublisher(v: String): DictionaryMapPublisher = DictionaryMapPublisher(v)

  implicit def toDictionaryMapPublisher(v: StreamId): DictionaryMapPublisher = DictionaryMapPublisher(v)

  case class DictionaryMapPublisher(s: StreamId) {

    def !#(values: Array[Any])(implicit dict: Dictionary): Unit = transition(values)

    private def transition(values: Array[Any])(implicit dict: Dictionary): Unit = performStateTransition(s, ?#(s) match {
      case Some(state) => state.transitionTo(values)
      case None => new DictionaryMapStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, values, dict)
    })

    def streamMapSnapshot(values: Array[Any])(implicit dict: Dictionary): Unit = transition(values)

    def !#(map: Map[String, Any])(implicit dict: Dictionary): Unit = transition(fromMap(map))

    def streamMapSnapshot(map: Map[String, Any])(implicit dict: Dictionary): Unit = transition(fromMap(map))

    private def fromMap(map: Map[String, Any])(implicit dict: Dictionary) = {
      val arr = Array.ofDim[Any](dict.fields.length)
      var idx = 0
      while (idx < dict.fields.length) {
        val nextField = dict.fields(idx)
        map.get(nextField) foreach { value => arr(idx) = value }
        idx += 1
      }
      arr
    }

    def !#(tuples: Tuple2[String, Any]*)(implicit dict: Dictionary): Unit = transition(fromMap(tuples.toMap))

    def streamMapSnapshot(tuples: Tuple2[String, Any]*)(implicit dict: Dictionary): Unit = transition(fromMap(tuples.toMap))

    def !#+(map: Map[String, Any])(implicit dict: Dictionary): Unit = transitionPut(fromMapPut(map))

    private def fromMapPut(map: Map[String, Any])(implicit dict: Dictionary) = {
      val arr = Array.ofDim[Any](dict.fields.length)
      var idx = 0
      while (idx < dict.fields.length) {
        val nextField = dict.fields(idx)
        map.get(nextField) match {
          case None => arr(idx) = NoChange
          case Some(value) => arr(idx) = value
        }
        idx += 1
      }
      arr
    }

    private def transitionPut(diffs: Array[Any])(implicit dict: Dictionary): Unit = ?#(s) match {
      case Some(state) => performStateTransition(s, DictionaryMapStreamTransitionPartial(state.seed, state.seq, state.seq + 1, diffs))
      case None =>
    }

    def streamMapPut(map: Map[String, Any])(implicit dict: Dictionary): Unit = transitionPut(fromMapPut(map))

    def !#+(tuples: Tuple2[String, Any]*)(implicit dict: Dictionary): Unit = transitionPut(fromMapPut(tuples.toMap))

    def streamMapPut(tuples: Tuple2[String, Any]*)(implicit dict: Dictionary): Unit = transitionPut(fromMapPut(tuples.toMap))


  }

}
