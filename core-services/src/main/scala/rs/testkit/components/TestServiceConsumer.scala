package rs.testkit.components

import rs.core.Subject
import rs.core.evt.{EvtSource, InfoE}
import rs.core.services.BaseServiceActor.StopRequest
import rs.core.services.Messages.{SignalAckFailed, SignalAckOk}
import rs.core.services.endpoint.Terminal

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object TestServiceConsumer {

  val EvtSourceId = "Test.ServiceConsumer"

  case object EvtStringUpdate extends InfoE

  case object EvtSetUpdate extends InfoE

  case object EvtMapUpdate extends InfoE

  case object EvtListUpdate extends InfoE

  case object EvtSubscribingTo extends InfoE

  case object EvtSignalResponseReceivedAckOk extends InfoE

  case object EvtSignalResponseReceivedAckFailed extends InfoE

  case object EvtSignalTimeout extends InfoE

  case class Open(svc: String, topic: String, keys: String = "")

  case class Close(svc: String, topic: String, keys: String = "")

  case class SendSignal(svc: String, topic: String, keys: String = "", payload: Any = None, expiry: FiniteDuration = 1 minute, orderingGroup: Option[Any] = None, correlationId: Option[Any] = None)

}

class TestServiceConsumer extends Terminal {

  import TestServiceConsumer._
  override val evtSource: EvtSource = EvtSourceId

  implicit val ec = context.dispatcher

  onMessage {
    case Open(s, t, k) =>
      raise(EvtSubscribingTo, 'sourceService -> s, 'topic -> t, 'keys -> k)
      subscribe(Subject(s, t, k))
    case Close(s, t, k) => unsubscribe(Subject(s, t, k))
    case x: SendSignal => signalAsk(Subject(x.svc, x.topic, x.keys), x.payload, x.expiry, x.orderingGroup, x.correlationId) onComplete {
      case Success(SignalAckOk(cid, subj, pay)) => raise(EvtSignalResponseReceivedAckOk, 'correlationId -> cid, 'subj -> subj, 'payload -> pay)
      case Success(SignalAckFailed(cid, subj, pay)) => raise(EvtSignalResponseReceivedAckFailed, 'correlationId -> cid, 'subj -> subj, 'payload -> pay)
      case Failure(e) => raise(EvtSignalTimeout, 'value -> e.getMessage)
      case _ =>
    }
    case StopRequest => context.parent ! StopRequest
  }



  onStringRecord {
    case (s, str) => raise(EvtStringUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> str)
  }

  onSetRecord {
    case (s, set) => raise(EvtSetUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> set.toList.map(_.toString).sorted.mkString(","))
  }

  onDictMapRecord {
    case (s, map) => raise(EvtMapUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> map.asMap)
  }

  onListRecord {
    case (s, list) => raise(EvtListUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> list.mkString(","))
  }

  onMessage {
    case m: SignalAckOk =>
  }
}
