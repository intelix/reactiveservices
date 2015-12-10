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
package rs.examples.counter


import rs.core.actors.ActorState
import rs.core.services.{ServiceEvt, StatefulServiceActor}
import rs.core.{Subject, TopicKey}
import rs.examples.counter.CounterService.CounterValue

import scala.concurrent.duration._
import scala.language.postfixOps

trait CounterServiceEvt extends ServiceEvt {

  val CounterReset = "CounterReset".info
  val NowTicking = "NowTicking".info
  val NowStopped = "NowStopped".info

  override def componentId: String = "CounterService"
}

object CounterServiceEvt extends CounterServiceEvt

object CounterService {
  val CounterTopic = "counter"

  case object Initial extends ActorState

  case object Ticking extends ActorState

  case object Stopped extends ActorState

  case class CounterValue(value: Int)

  case object Tick

  case object Reset

  case object Stop

  case object Start

}

class CounterService(id: String) extends StatefulServiceActor[CounterValue](id) with CounterServiceEvt {

  import CounterService._

  startWith(Initial, CounterValue(0))
  self ! Start

  when(Initial) {
    case Event(Start, _) => transitionTo(Ticking)
    case Event(Stop, _) => transitionTo(Stopped)
  }

  when(Ticking) {
    case Event(Tick, CounterValue(v)) =>
      CounterTopic !~ v.toString
      stay() using CounterValue(v + 1)
    case Event(Stop, _) => transitionTo(Stopped)
  }

  when(Stopped) {
    case Event(Start, _) => transitionTo(Ticking)
  }

  otherwise {
    case Event(Reset, _) =>
      CounterReset()
      stay() using CounterValue(0)
    case Event(Stop, _) => stay()
    case Event(Start, _) => stay()
  }

  onTransition {
    case _ -> Ticking =>
      NowTicking('current -> stateData.value)
      setTimer("ticker", Tick, 2 seconds, repeat = true)
    case _ -> Stopped =>
      NowStopped('current -> stateData.value)
      cancelTimer("ticker")
  }

  onSubjectMapping {
    case Subject(_, TopicKey("counter"), _) => Some(CounterTopic)
  }

  onSignal {
    case (Subject(_, TopicKey("reset"), _), _) =>
      self ! Reset
      SignalOk()
    case (Subject(_, TopicKey("start"), _), _) =>
      self ! Start
      SignalOk()
    case (Subject(_, TopicKey("stop"), _), _) =>
      self ! Stop
      SignalOk()
  }

}
