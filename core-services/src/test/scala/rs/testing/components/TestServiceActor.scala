package rs.testing.components

import rs.core.SubjectKeys.UserId
import rs.core.actors.ClusterAwareness
import rs.core.config.ConfigOps.wrap
import rs.core.registry.RegistryRef
import rs.core.services.ServiceCell.StopRequest
import rs.core.services.{ServiceCell, ServiceCellSysevents, StreamId}
import rs.core.stream.DictionaryMapStreamState.Dictionary
import rs.core.stream.ListStreamState.{FromTail, FromHead, RejectAdd, ListSpecs}
import rs.core.stream.SetStreamState.SetSpecs
import rs.core.{Subject, TopicKey}
import rs.testing.components.TestServiceActor._

object TestServiceActor {

  val AutoStringReply = "hello"
  val AutoSetReply = Set("a", "b")
  val AutoMapReply = Array("a", 1, true)
  val AutoListReply = List("1","2","3","4")

  case class PublishString(streamId: StreamId, v: String)


  case class PublishSet(streamId: StreamId, v: Set[String])

  case class PublishSetAdd(streamId: StreamId, v: Set[String])

  case class PublishSetRemove(streamId: StreamId, v: Set[String])

  case class PublishMap(streamId: StreamId, v: Array[Any])
  case class PublishMapAdd(streamId: StreamId, v: Tuple2[String, Any])

  case class PublishList(streamId: StreamId, v: List[String], specs: ListSpecs)
  case class PublishListAdd(streamId: StreamId, pos: Int, v: String)
  case class PublishListRemove(streamId: StreamId, pos: Int)
  case class PublishListReplace(streamId: StreamId, pos: Int, v: String)
  case class PublishListFindRemove(streamId: StreamId, original: String)
  case class PublishListFindReplace(streamId: StreamId, original: String, v: String)


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

class TestServiceActor(id: String) extends ServiceCell(id) with Evt with ClusterAwareness with RegistryRef {

  var signalCounter = 0

  IntConfigValue('value -> serviceCfg.asInt("int-config-value", 0))

  implicit val setSpecs = SetSpecs(allowPartialUpdates = true)
  implicit val mapDictionary = Dictionary("s", "i", "b")

  val listSpecsRejectAdd = ListSpecs(5, RejectAdd)
  val listSpecsFromHead = ListSpecs(5, FromHead)
  val listSpecsFromTail = ListSpecs(5, FromTail)

  onStreamActive {
    case s@StreamId("string", x) =>
      StreamActive('stream -> s)
      StreamId("string", x) !~ TestServiceActor.AutoStringReply
    case s@StreamId("set", x) =>
      StreamActive('stream -> s)
      StreamId("set", x) !% TestServiceActor.AutoSetReply
    case s@StreamId("map", x) =>
      StreamActive('stream -> s)
      StreamId("map", x) !# TestServiceActor.AutoMapReply
    case s@StreamId("list1", x) =>
      StreamActive('stream -> s)
      implicit val specs = listSpecsRejectAdd
      StreamId("list1", x) !:! TestServiceActor.AutoListReply
    case s@StreamId("list2", x) =>
      StreamActive('stream -> s)
      implicit val specs = listSpecsFromHead
      StreamId("list2", x) !:! TestServiceActor.AutoListReply
    case s@StreamId("list3", x) =>
      StreamActive('stream -> s)
      implicit val specs = listSpecsFromTail
      StreamId("list3", x) !:! TestServiceActor.AutoListReply
  }

  onStreamPassive {
    case s@StreamId("string", x) => StreamPassive('stream -> s)
    case s@StreamId("set", x) => StreamPassive('stream -> s)
    case s@StreamId("map", x) => StreamPassive('stream -> s)
    case s@StreamId("list1", x) => StreamPassive('stream -> s)
    case s@StreamId("list2", x) => StreamPassive('stream -> s)
    case s@StreamId("list3", x) => StreamPassive('stream -> s)
  }

  onSubjectSubscription {
    case Subject(_, TopicKey("string"), _) => Some("string")
    case Subject(_, TopicKey("string1"), _) => Some("string")
    case Subject(_, TopicKey("string2"), _) => Some("string")
    case Subject(_, TopicKey("stringWithId"), UserId(i)) => Some(StreamId("string", Some(i)))

    case Subject(_, TopicKey("set"), _) => Some("set")
    case Subject(_, TopicKey("map"), _) => Some("map")
    case Subject(_, TopicKey("list1"), _) => Some("list1")
    case Subject(_, TopicKey("list2"), _) => Some("list2")
    case Subject(_, TopicKey("list3"), _) => Some("list3")
  }

  onMessage {
    case StopRequest => context.parent ! StopRequest
    case PublishString(sId, v) => sId !~ v
    case PublishSet(sId, v) => sId !% v
    case PublishSetAdd(sId, v) => v.foreach(sId !%+ _)
    case PublishSetRemove(sId, v) => v.foreach(sId !%- _)
    case PublishMap(sId, v) => sId !# v
    case PublishMapAdd(sId, v) => sId !#+ v
    case PublishList(sId, v, specs) =>
      implicit val s = specs
      sId !:! v
    case PublishListAdd(sId, p, v) => sId !:+(p, v)
    case PublishListRemove(sId, p) => sId !:- p
    case PublishListReplace(sId, p, v) => sId !:*(p, v)
    case PublishListFindRemove(sId, v) => sId !:-? v
    case PublishListFindReplace(sId, o, v) => sId !:*?(o, v)
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

