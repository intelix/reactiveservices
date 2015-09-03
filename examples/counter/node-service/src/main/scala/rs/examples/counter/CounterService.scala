package rs.examples.counter


import rs.core.actors.ActorWithTicks
import rs.core.services.ServiceCell
import rs.core.services.internal.StringStreamRef
import rs.core.stream.DictionaryMapStreamState.Dictionary
import rs.core.stream.ListStreamState.{FromHead, ListSpecs}
import rs.core.stream.SetStreamState.SetSpecs
import rs.core.stream.{DictionaryMapStreamPublisher, ListStreamPublisher, SetStreamPublisher, StringStreamPublisher}
import rs.core.{ServiceKey, Subject, TopicKey}

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
      println(s"!>>> before map publish")
      "map" !# Array[Any]("abc" + cnt, cnt, true,"a","1234567890")
      println(s"!>>> after map publish")
      scheduleOnce(2 seconds, "tick")
  }

  onSignal {
    case (_, s) =>
      Some(SignalOk(Some("Well done - " + s)))
    //    None
  }

  onStreamActive {
    case StringStreamRef("counterlist") => "counterlist" !:! List.empty
    case StringStreamRef("counterset") => "counterset" !% Set.empty

    case StringStreamRef("token") => "token" !~ "tok123"
    case StringStreamRef("permissions") => "permissions" !~ "allow_*"

    case key => logger.info(s"!>>>> Stream active $key")
  }

  onStreamPassive {
    case key => logger.info(s"!>>>> Stream passive $key")
  }

  subjectToStreamKey {
    case Subject(_, TopicKey("token"), _) => "token"
    case Subject(_, TopicKey("permissions"), _) => "permissions"

    case Subject(_, TopicKey("string"), _) => "ticker"
    case Subject(_, TopicKey("list"), _) => "counterlist"
    case Subject(_, TopicKey("set"), _) => "counterset"
    case Subject(_, TopicKey("map"), _) => "map"
  }

}