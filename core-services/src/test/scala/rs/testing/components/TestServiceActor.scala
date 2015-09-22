package rs.testing.components

import rs.core.SubjectKeys.UserId
import rs.core.actors.ClusterAwareness
import rs.core.config.ConfigOps.wrap
import rs.core.registry.RegistryRef
import rs.core.services.{ServiceCell, ServiceCellSysevents, StreamId}
import rs.core.stream.StringStreamPublisher
import rs.core.{Subject, TopicKey}
import rs.testing.components.TestServiceActor.{PublishString, Evt}

object TestServiceActor {

  val AutoStringReply = "hello"

  case class PublishString(streamId: StreamId, v: String)

  trait Evt extends ServiceCellSysevents {

    val IntConfigValue = "IntConfigValue".info
    val OtherServiceLocationChanged = "OtherServiceLocationChanged".info
    val StreamActive = "StreamActive".info
    val StreamPassive = "StreamPassive".info
    val SignalReceived = "SignalReceived".info

    override def componentId: String = "Test"
  }

  object Evt extends Evt

}

class TestServiceActor(id: String) extends ServiceCell(id) with Evt with ClusterAwareness with StringStreamPublisher with RegistryRef {

  var signalCounter = 0

  IntConfigValue('value -> serviceCfg.asInt("int-config-value", 0))

  onStreamActive {
    case s@StreamId("string", x) =>
      StreamActive('stream -> s)
      StreamId("string",x) !~ TestServiceActor.AutoStringReply
  }

  onStreamPassive {
    case s@StreamId("string", x) =>
      StreamPassive('stream -> s)
  }

  onSubjectSubscription {
    case Subject(_, TopicKey("string"), _) => Some("string")
    case Subject(_, TopicKey("string1"), _) => Some("string")
    case Subject(_, TopicKey("string2"), _) => Some("string")
    case Subject(_, TopicKey("stringWithId"), UserId(i)) => Some(StreamId("string", Some(i)))
  }

  onMessage {
    case PublishString(sId, v) => sId !~ v
  }

  onSignal {
    case (subj@Subject(_, TopicKey("signal"), _), s) =>
      signalCounter += 1
      SignalReceived('subj -> subj, 'payload -> s)
      Some(SignalOk(Some(s.toString + signalCounter)))
    case (subj@Subject(_, TopicKey("signal_no_response"), _), _) =>
      SignalReceived('subj -> subj)
      None
    case (subj@Subject(_, TopicKey("signal_failure"), _), _) =>
      SignalReceived('subj -> subj)
      Some(SignalFailed(Some("failure")))
  }

  registerServiceLocationInterest("test1")

  onServiceLocationChanged {
    case (s, l) => OtherServiceLocationChanged('addr -> l, 'service -> s.id)
  }

}

