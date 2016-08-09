package au.com.intelix.rs.core.testkit.components

import au.com.intelix.rs.core.SubjectTags.UserId
import au.com.intelix.rs.core.actors.ClusterAwareness
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.evt.{EvtSource, InfoE}
import au.com.intelix.rs.core.registry.RegistryRef
import au.com.intelix.rs.core.services._
import au.com.intelix.rs.core.stream.DictionaryMapStreamState.Dictionary
import au.com.intelix.rs.core.stream.ListStreamState.{FromHead, FromTail, ListSpecs, RejectAdd}
import au.com.intelix.rs.core.stream.SetStreamState.SetSpecs
import au.com.intelix.rs.core.{Subject, TopicKey}

object TestServiceActor {


  object Evt {
    case object IntConfigValue extends InfoE
    case object OtherServiceLocationChanged extends InfoE
    case object StreamActive extends InfoE
    case object StreamPassive extends InfoE
    case object SignalReceived extends InfoE
  }


  val AutoStringReply = "hello"
  val AutoSetReply = Set[Any]("a", "b")
  val AutoMapReply = Array("a", 1, true)
  val AutoListReply = List("1", "2", "3", "4")

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

}

class TestServiceActor extends StatelessServiceActor with ClusterAwareness with RegistryRef {

  import TestServiceActor._

  var signalCounter = 0

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    raise(Evt.IntConfigValue, 'value -> serviceCfg.asInt("int-config-value", 0))
  }

  implicit val setSpecs = SetSpecs(allowPartialUpdates = true)
  implicit val mapDictionary = Dictionary("s", "i", "b")

  val listSpecsRejectAdd = ListSpecs(5, RejectAdd)
  val listSpecsFromHead = ListSpecs(5, FromHead)
  val listSpecsFromTail = ListSpecs(5, FromTail)

  onStreamActive {
    case s@CompoundStreamId("string", x) =>
      raise(Evt.StreamActive, 'stream -> s)
      CompoundStreamId("string", x) !~ TestServiceActor.AutoStringReply
    case s@SimpleStreamId("string") =>
      raise(Evt.StreamActive, 'stream -> s)
      SimpleStreamId("string") !~ TestServiceActor.AutoStringReply
    case s@SimpleStreamId("stringX") =>
      raise(Evt.StreamActive, 'stream -> s)
      SimpleStreamId("stringX") !~ TestServiceActor.AutoStringReply + "X"
    case s@SimpleStreamId("set") =>
      raise(Evt.StreamActive, 'stream -> s)
      SimpleStreamId("set") !% TestServiceActor.AutoSetReply
    case s@SimpleStreamId("map") =>
      raise(Evt.StreamActive, 'stream -> s)
      SimpleStreamId("map") !# TestServiceActor.AutoMapReply
    case s@SimpleStreamId("list1") =>
      raise(Evt.StreamActive, 'stream -> s)
      implicit val specs = listSpecsRejectAdd
      SimpleStreamId("list1") !:! TestServiceActor.AutoListReply
    case s@SimpleStreamId("list2") =>
      raise(Evt.StreamActive, 'stream -> s)
      implicit val specs = listSpecsFromHead
      SimpleStreamId("list2") !:! TestServiceActor.AutoListReply
    case s@SimpleStreamId("list3") =>
      raise(Evt.StreamActive, 'stream -> s)
      implicit val specs = listSpecsFromTail
      SimpleStreamId("list3") !:! TestServiceActor.AutoListReply
  }

  onStreamPassive {
    case s@CompoundStreamId("string", x) => raise(Evt.StreamPassive, 'stream -> s)
    case s@SimpleStreamId("string") => raise(Evt.StreamPassive, 'stream -> s)
    case s@SimpleStreamId("set") => raise(Evt.StreamPassive, 'stream -> s)
    case s@SimpleStreamId("map") => raise(Evt.StreamPassive, 'stream -> s)
    case s@SimpleStreamId("list1") => raise(Evt.StreamPassive, 'stream -> s)
    case s@SimpleStreamId("list2") => raise(Evt.StreamPassive, 'stream -> s)
    case s@SimpleStreamId("list3") => raise(Evt.StreamPassive, 'stream -> s)
  }

  onSubjectMapping {
    case Subject(_, TopicKey("string"), _) => "string"
    case Subject(_, TopicKey("string1"), _) => "string"
    case Subject(_, TopicKey("string2"), _) => "string"
    case Subject(_, TopicKey("stringX"), _) => "stringX"
    case Subject(_, TopicKey("stringWithId"), UserId(i)) => CompoundStreamId("string", i)

    case Subject(_, TopicKey("set"), _) => "set"
    case Subject(_, TopicKey("map"), _) => "map"
    case Subject(_, TopicKey("list1"), _) => "list1"
    case Subject(_, TopicKey("list2"), _) => "list2"
    case Subject(_, TopicKey("list3"), _) => "list3"
  }

  onMessage {
    case PublishString(sId, v) => sId !~ v
    case PublishSet(sId, v) => sId !% v.asInstanceOf[Set[Any]]
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
      raise(Evt.SignalReceived, 'subj -> subj, 'payload -> s)
      Some(SignalOk(Some(s.toString + signalCounter)))
    case (subj@Subject(_, TopicKey("signal_no_response"), _), _) =>
      raise(Evt.SignalReceived, 'subj -> subj)
      None
    case (subj@Subject(_, TopicKey("signal_failure"), _), _) =>
      raise(Evt.SignalReceived, 'subj -> subj)
      Some(SignalFailed(Some("failure")))
  }

  registerServiceLocationInterest("test1")

  onServiceLocationChanged {
    case (s, l) => raise(Evt.OtherServiceLocationChanged, 'addr -> l, 'service -> s.id)
  }
}

