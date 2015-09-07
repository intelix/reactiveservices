package rs.examples.counter


import rs.core.actors.ActorWithTicks
import rs.core.services.{StreamId, ServiceCell}
import rs.core.stream.DictionaryMapStreamState.Dictionary
import rs.core.stream.ListStreamState.{FromHead, ListSpecs}
import rs.core.stream.SetStreamState.SetSpecs
import rs.core.stream.{DictionaryMapStreamPublisher, ListStreamPublisher, SetStreamPublisher, StringStreamPublisher}
import rs.core.{Subject, TopicKey}

import scala.concurrent.duration._
import scala.language.postfixOps

class CounterService(id: String) extends ServiceCell(id) with StringStreamPublisher with ListStreamPublisher with SetStreamPublisher with DictionaryMapStreamPublisher with ActorWithTicks {

  var cnt = 0

  implicit val listSpecs: ListSpecs = ListSpecs(10, FromHead)
  implicit val setSpecs: SetSpecs = SetSpecs(allowPartialUpdates = true)
  implicit val dictionary: Dictionary = Dictionary("a", "b", "c", "d", "e")

  scheduleOnce(2 seconds, "tick")

  onMessage {
    case "tick" =>
      cnt += 1
      "ticker" !~ ("hello-" + cnt)
      "counterlist" !:+(-1, "element-" + cnt)
      "counterset" !%+ "set-" + cnt
      "counterset" !%- "set-" + (cnt - 20)
      "map" !# Array[Any]("abc" + cnt, cnt, true,"a","1234567890")
      scheduleOnce(2 seconds, "tick")
  }

  onSignal {
    case (_, s) =>
      Some(SignalOk(Some("Well done - " + s)))
    //    None
  }

  onStreamActive {
    case StreamId("counterlist", _) => "counterlist" !:! List.empty
    case StreamId("counterset", _) => "counterset" !% Set.empty

    case StreamId("token", _) => "token" !~ "tok123"
    case StreamId("permissions", _) => "permissions" !~ "allow_*"

    case key => logger.info(s"!>>>> Stream active $key")
  }

  onStreamPassive {
    case key => logger.info(s"!>>>> Stream passive $key")
  }

  onSubjectSubscription {
    case Subject(_, TopicKey("token"), _) => Some("token")
    case Subject(_, TopicKey("permissions"), _) => Some("permissions")

    case Subject(_, TopicKey("string"), _) => Some("ticker")
    case Subject(_, TopicKey("list"), _) => Some("counterlist")
    case Subject(_, TopicKey("set"), _) => Some("counterset")
    case Subject(_, TopicKey("map"), _) => Some("map")
  }

}