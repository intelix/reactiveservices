package rs.core.stream

import java.util

import rs.core.Subject
import rs.core.javaapi.JServiceCell
import rs.core.services.{StreamId, ServiceCell}
import rs.core.services.endpoint.StreamConsumer
import rs.core.stream.DictionaryMapStreamState.{Dictionary, NoChange}

import scala.language.implicitConversions


object DictionaryMapStreamState {

  case object NoChange

  case class Dictionary(fields: Array[String]) {
    def locateIdx(field: String) = {
      var idx = 0
      while (idx < fields.length && fields(idx) != field) {
        idx += 1
      }
      if (fields(idx) == field) idx else -1
    }

    @transient lazy val asString: String = "Dictionary(" + fields.mkString(",") + ")"

    override def toString: String = asString
  }

  object Dictionary {
    def apply(s: String*): Dictionary = Dictionary(s.toArray)
  }


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


}


case class DictionaryMapStreamState(seed: Int, seq: Int, values: Array[Any], dict: Dictionary) extends StreamState with StreamStateTransition {

  def transitionTo(a: Array[Any]) = DictionaryMapStreamTransitionPartial(seed, seq, seq + 1, DictionaryMapStreamState.calculateDiff(values, a))

  override def transitionFrom(olderState: Option[StreamState]): Option[StreamStateTransition] = olderState match {
    case Some(st@DictionaryMapStreamState(otherSeed, otherSeq, a, _)) if otherSeed == seed && a.length == values.length =>
      Some(DictionaryMapStreamTransitionPartial(seed, otherSeq, seq, DictionaryMapStreamState.calculateDiff(a, values)))
    case _ => Some(this)
  }

  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = Some(this)

  override def applicableTo(state: Option[StreamState]): Boolean = true

  @transient lazy val asString: String = "DictionaryMapStreamState(" + seed + "," + seq + "," + dict.fields.zip(values).map { case (a, b) => a + "=" + b }.mkString("{", ",", "}") + ")"

  override def toString: String = asString
}


case class DictionaryMapStreamTransitionPartial(seed: Int, seq: Int, seq2: Int, diffs: Array[Any]) extends StreamStateTransition {
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

  override def toNewStateFrom(state: Option[StreamState]): Option[StreamState] = state match {
    case Some(st@DictionaryMapStreamState(otherSeed, otherSeq, a, _)) if otherSeed == seed && otherSeq == seq && a.length == diffs.length =>
      Some(st.copy(seq = seq2, values = applyDiffsTo(a)))
    case _ => None
  }

  override def applicableTo(state: Option[StreamState]): Boolean = state match {
    case Some(DictionaryMapStreamState(otherSeed, otherSeq, a, _)) if otherSeed == seed && otherSeq == seq && a.length == diffs.length => true
    case _ => false
  }
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
}

trait DictionaryMapStreamConsumer extends StreamConsumer {

  type DictionaryMapStreamConsumer = PartialFunction[(Subject, DictionaryMap), Unit]

  def toMap(values: Array[Any], dict: Dictionary): DictionaryMap = new DictionaryMap(dict, values)

  onStreamUpdate {
    case (s, x: DictionaryMapStreamState) => composedFunction(s, toMap(x.values, x.dict))
  }

  final def onDictMapRecord(f: DictionaryMapStreamConsumer) =
    composedFunction = f orElse composedFunction

  private var composedFunction: DictionaryMapStreamConsumer = {
    case _ =>
  }

}

trait JDictionaryMapStreamPublisher extends DictionaryMapStreamPublisher {
  self: JServiceCell =>

  import scala.collection.JavaConversions._

  def streamMapSnapshot(streamRef: String, values: Array[Any], dict: Dictionary) = streamRef.!#(values)(dict)
  def streamMapSnapshot(streamRef: String, values: util.Map[String, Any], dict: Dictionary) = streamRef.!#(values.toMap)(dict)

}

trait DictionaryMapStreamPublisher {
  self: ServiceCell =>

  def ?#(s: StreamId): Option[DictionaryMapStreamState] = currentStreamState(s) flatMap {
    case s: DictionaryMapStreamState => Some(s)
    case _ => None
  }

  implicit def toDictionaryMapPublisher(v: String): DictionaryMapPublisher = DictionaryMapPublisher(v)

  implicit def toDictionaryMapPublisher(v: StreamId): DictionaryMapPublisher = DictionaryMapPublisher(v)

  case class DictionaryMapPublisher(s: StreamId) {

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


    private def transition(values: Array[Any])(implicit dict: Dictionary): Unit = performStateTransition(s, ?#(s) match {
      case Some(state) => state.transitionTo(values)
      case None => new DictionaryMapStreamState((System.nanoTime() % Int.MaxValue).toInt, 0, values, dict)
    })

    def !#(values: Array[Any])(implicit dict: Dictionary): Unit = transition(values)

    def streamMapSnapshot(values: Array[Any])(implicit dict: Dictionary): Unit = transition(values)

    def !#(map: Map[String, Any])(implicit dict: Dictionary): Unit = transition(fromMap(map))

    def streamMapSnapshot(map: Map[String, Any])(implicit dict: Dictionary): Unit = transition(fromMap(map))

    def !#(tuples: Tuple2[String, Any]*)(implicit dict: Dictionary): Unit = transition(fromMap(tuples.toMap))

    def streamMapSnapshot(tuples: Tuple2[String, Any]*)(implicit dict: Dictionary): Unit = transition(fromMap(tuples.toMap))

  }

}
