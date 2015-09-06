package rs.node.testing

import rs.core.actors.ActorWithTicks
import rs.core.services.ServiceCell
import rs.core.stream.ListStreamState.{FromHead, ListSpecs}
import rs.core.stream.{ListStreamPublisher, StringStreamPublisher}
import rs.core.{ServiceKey, Subject, TopicKey}

import scala.concurrent.duration._
import scala.language.postfixOps

class TestPublisherActor2(id: String) extends ServiceCell(id) with StringStreamPublisher with ListStreamPublisher with ActorWithTicks {
//  override val serviceKey: ServiceKey = "test-publisher2"

  var cnt = 0

  implicit val listSpecs: ListSpecs = ListSpecs(10, FromHead)

  scheduleOnce(500 millis, "tick")

  onMessage {
    case "tick" =>
      cnt += 1
      "ticker" !~ ("test2-" + cnt)
      scheduleOnce(500 millis, "tick")
  }

  onStreamActive {
    case key => logger.info(s"!>>>> Stream active $key")
  }

  onStreamPassive {
    case key => logger.info(s"!>>>> Stream passive $key")
  }

  onSubjectSubscription {
    case Subject(_, TopicKey("string"), _) => Some("ticker")
    case Subject(_, TopicKey("list"), _) => Some("counterlist")
  }

}