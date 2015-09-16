package rs.core

import rs.core.services.Messages.SignalAckOk
import rs.core.services.endpoint.Terminal

import scala.util.Success

class TestServiceConsumer(id: String) extends Terminal {

  implicit val ec = context.dispatcher

  subscribe(Subject("test", "string", ""))

  onStringRecord {
    case (s, str) =>
      logger.info(s"!>>>>> STRING: " + str)
      val from = System.nanoTime()
      signal(s, "wow: " + from)
      signalAsk(s, "Hey - " + str) onComplete {
        case Success(x) =>
          val elapsed = System.nanoTime() - from
          println(s"!>>>> RECEIVED COMMAND RESPONSE $x, elapsed : " + elapsed)
        case x => println("!>>>>> RECEIVED COMMAND RESPONSE: " + x)
      }
  }

  onListRecord {
    case (s, list) => logger.info(s"!>>>>> LIST: " + list)
  }

  onMessage {
    case m:SignalAckOk => logger.info("!>>>> " + m + " ; " + System.nanoTime())
  }


  override def componentId: String = "TestConsumer"

}
