package au.com.intelix.evt.testkit

import org.scalatest.matchers.{MatchResult, Matcher}
import au.com.intelix.evt._

import scala.util.matching.Regex

trait EvtMatchers {

  class ContainsAllFields(e: EvtSelection, count: Option[Range], values: Seq[EvtFieldValue]) extends Matcher[List[RaisedEvent]] {
    def apply(all: List[RaisedEvent]) = {
      val left = all.filter { x => x.event == e.e && (e.s.isEmpty || e.s.contains(x.source)) }
      val found = if (values.isEmpty) left.size
      else left.count(nextRaised =>
        !values.exists { kv =>
          !nextRaised.values.exists { v =>
            v._1 == kv._1 && (kv._2 match {
              case x: Regex => x.findFirstMatchIn(v._2.toString).isDefined
              case EventFieldMatcher(f) => f(v._2.toString)
              case x if x.getClass == v._2.getClass => v._2 == x
              case x => String.valueOf(v._2) == String.valueOf(x)
            })
          }
        })
      MatchResult(
        count match {
          case Some(req) => req.contains(found)
          case None => found > 0
        },
        s"[$e] with values $values should be raised $count times, matching found = $found, total events inspected = ${left.size}", s"Found [$e] with values $values, count=$found"
      )
    }
  }

  def haveAllValues(e: EvtSelection, count: Range, values: Seq[EvtFieldValue]) = new ContainsAllFields(e, Some(count), values)

  def haveAllValues(e: EvtSelection, values: Seq[EvtFieldValue]) = new ContainsAllFields(e, None, values)
}
