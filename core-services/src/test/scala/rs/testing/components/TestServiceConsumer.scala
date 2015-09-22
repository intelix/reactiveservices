package rs.testing.components

import rs.core.Subject
import rs.core.actors.WithGlobalConfig
import rs.core.services.Messages.{SignalAckFailed, Signal, SignalAckOk}
import rs.core.services.ServiceCellSysevents
import rs.core.services.endpoint.Terminal
import rs.testing.components.TestServiceConsumer.{SendSignal, Close, Evt, Open}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object TestServiceConsumer {

  trait Evt extends ServiceCellSysevents {
    val StringUpdate = "StringUpdate".info
    val SubscribingTo = "SubscribingTo".info

    val SignalResponseReceivedAckOk = "SignalResponseReceivedAckOk".info
    val SignalResponseReceivedAckFailed = "SignalResponseReceivedAckFailed".info
    val SignalTimeout = "SignalTimeout".info

    override def componentId: String = "TestServiceConsumer"
  }

  object Evt extends Evt

  case class Open(svc: String, topic: String, keys: String = "")

  case class Close(svc: String, topic: String, keys: String = "")

  case class SendSignal(svc: String, topic: String, keys: String = "", payload: Any = None, expiry: FiniteDuration = 1 minute, orderingGroup: Option[Any] = None, correlationId: Option[Any] = None)

}

class TestServiceConsumer(id: String) extends WithGlobalConfig with Terminal with Evt {

  implicit val ec = context.dispatcher

  onMessage {
    case Open(s, t, k) =>
      SubscribingTo('sourceService -> s, 'topic -> t, 'keys -> k)
      subscribe(Subject(s, t, k))
    case Close(s, t, k) => unsubscribe(Subject(s, t, k))
    case x: SendSignal => signalAsk(Subject(x.svc, x.topic, x.keys), x.payload, x.expiry, x.orderingGroup, x.correlationId) onComplete {
      case Success(SignalAckOk(cid, subj, pay)) => SignalResponseReceivedAckOk('correlationId -> cid, 'subj -> subj, 'payload -> pay)
      case Success(SignalAckFailed(cid, subj, pay)) => SignalResponseReceivedAckFailed('correlationId -> cid, 'subj -> subj, 'payload -> pay)
      case Failure(e) => SignalTimeout('value -> e.getMessage)
      case _ =>
    }
  }



  onStringRecord {
    case (s, str) =>
      StringUpdate('sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.keys, 'value -> str)
  }

  onListRecord {
    case (s, list) =>
  }

  onMessage {
    case m: SignalAckOk =>
  }


}
