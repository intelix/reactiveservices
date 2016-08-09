package au.com.intelix.rs.core.testkit.components

import au.com.intelix.evt.{EvtSource, InfoE}
import au.com.intelix.rs.core.Subject
import au.com.intelix.rs.core.services.BaseServiceActor.StopRequest
import au.com.intelix.rs.core.services.Messages.{SignalAckFailed, SignalAckOk}
import au.com.intelix.rs.core.services.endpoint.{SignalProducer, SubscriptionTerminal}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object TestServiceConsumer {

  object Evt {
    case object StringUpdate extends InfoE
    case object SetUpdate extends InfoE
    case object MapUpdate extends InfoE
    case object ListUpdate extends InfoE
    case object SubscribingTo extends InfoE
    case object SignalResponseReceivedAckOk extends InfoE
    case object SignalResponseReceivedAckFailed extends InfoE
    case object SignalTimeout extends InfoE
  }

  case class Open(svc: String, topic: String, keys: String = "")

  case class Close(svc: String, topic: String, keys: String = "")

  case class SendSignal(svc: String, topic: String, keys: String = "", payload: Any = None, expiry: FiniteDuration = 1 minute, orderingGroup: Option[Any] = None, correlationId: Option[Any] = None)

}

class TestServiceConsumer extends SubscriptionTerminal with SignalProducer {

  import TestServiceConsumer._

  implicit val ec = context.dispatcher

  onMessage {
    case Open(s, t, k) =>
      raise(Evt.SubscribingTo, 'sourceService -> s, 'topic -> t, 'keys -> k)
      subscribe(Subject(s, t, k))
    case Close(s, t, k) => unsubscribe(Subject(s, t, k))
    case x: SendSignal => signalAsk(Subject(x.svc, x.topic, x.keys), x.payload, x.expiry, x.orderingGroup, x.correlationId) onComplete {
      case Success(SignalAckOk(cid, subj, pay)) => raise(Evt.SignalResponseReceivedAckOk, 'correlationId -> cid, 'subj -> subj, 'payload -> pay)
      case Success(SignalAckFailed(cid, subj, pay)) => raise(Evt.SignalResponseReceivedAckFailed, 'correlationId -> cid, 'subj -> subj, 'payload -> pay)
      case Failure(e) => raise(Evt.SignalTimeout, 'value -> e.getMessage)
      case _ =>
    }
    case StopRequest => context.parent ! StopRequest
  }



  onStringRecord {
    case (s, str) => raise(Evt.StringUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> str)
  }

  onSetRecord {
    case (s, set) => raise(Evt.SetUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> set.toList.map(_.toString).sorted.mkString(","))
  }

  onDictMapRecord {
    case (s, map) => raise(Evt.MapUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> map.asMap)
  }

  onListRecord {
    case (s, list) => raise(Evt.ListUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> list.mkString(","))
  }

  onMessage {
    case m: SignalAckOk =>
  }
}
