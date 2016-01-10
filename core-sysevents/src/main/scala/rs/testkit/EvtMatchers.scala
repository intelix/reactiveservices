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
package rs.testkit

import org.scalatest.matchers.{MatchResult, Matcher}
import rs.core.evt.EvtFieldValue

import scala.util.matching.Regex

case class EventFieldMatcher(f: String => Boolean)

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
              case x => v._2 == x
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
