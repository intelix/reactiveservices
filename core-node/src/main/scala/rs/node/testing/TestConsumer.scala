package rs.node.testing

import rs.core.Subject
import rs.core.services.Messages
import rs.core.services.Messages.SignalAckOk
import rs.core.services.endpoint.Terminal

import scala.language.postfixOps
import scala.util.Success


class TestConsumer extends Terminal {

  implicit val ec = context.dispatcher

  subscribe(Subject("test-publisher", "string", ""))
  subscribe(Subject("test-publisher", "list", ""))



  onStringRecord {
    case (s, str) =>
      logger.info(s"!>>>>> STRING: " + str)
      val from = System.nanoTime()
          signal(s, "wow: " + from)
          signalAsk(s, "Hey - " + str) onComplete {
            case Success(x) =>
              val elapsed = System.nanoTime() - from
              logger.info(s"!>>>>> RECEIVED COMMAND RESPONSE $x, elapsed : " + elapsed)
            case x => logger.info("!>>>>> RECEIVED COMMAND RESPONSE: " + x)
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