package au.com.intelix.rs.core.serializer

import akka.actor._
import akka.util.ByteString
import au.com.intelix.rs.core.{ServiceKey, Subject, TopicKey}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import au.com.intelix.rs.core.serializer.RSThreadUnsafeCodec._
import au.com.intelix.rs.core.services.BaseServiceActor._
import au.com.intelix.rs.core.services.Messages.{SignalAckFailed, SignalAckOk}
import au.com.intelix.rs.core.services.internal.InternalMessages.{DownstreamDemandRequest, SignalPayload, StreamUpdate}
import au.com.intelix.rs.core.services.internal.acks.Acknowledgement
import au.com.intelix.rs.core.services._
import au.com.intelix.rs.core.stream.DictionaryMapStreamState.Dictionary
import au.com.intelix.rs.core.stream.ListStreamState.{FromHead, ListSpecs}
import au.com.intelix.rs.core.stream.SetStreamState.SetSpecs
import au.com.intelix.rs.core.stream.{DictionaryMapStreamState, ListStreamState, SetStreamState, StringStreamState}
import au.com.intelix.rs.core.{Subject, TopicKey}


class TestActor() extends Actor {
  override def receive: Receive = {
    case _ =>
  }
}

class RSThreadUnsafeCodecTest extends FlatSpec with Matchers with BeforeAndAfterAll {

  implicit val ec: ExternalCodecs = new ExternalCodecs {
    override def resolveActorRef(s: String): ActorRef = null
  }

  def builder = ByteString.newBuilder
  def kryo = new KryoCtx()


  lazy val actorSystem = ActorSystem()
  lazy val actorRef = actorSystem.actorOf(Props(classOf[TestActor]))


  "EntityType" should "convert correctly for all valid values" in {
    for {i <- 0 to 32767} {
      implicit val b = builder
      implicit val k = kryo
      EntityType.put(i)
      implicit val it = b.result().iterator
      EntityType.get() should equal(i)
    }
  }

  it should "fail for all illegal values" in {
    for {i <- 32768 to 40000} {
      implicit val b = builder
      implicit val k = kryo
      an[Exception] should be thrownBy EntityType.put(i)
    }
  }

  it should "have expected length when encoded" in {
    for {i <- 0 to 127} {
      implicit val b = builder
      implicit val k = kryo
      EntityType.put(i)
      b.result().size should equal(1)
    }
    for {i <- 128 to 32767} {
      implicit val b = builder
      implicit val k = kryo
      EntityType.put(i)
      b.result().size should equal(2)
    }
  }

  override protected def afterAll(): Unit = actorSystem.terminate()

  private def validate(i: Any, maybeValidateLen: Option[Int]): Unit = {
    implicit val b = builder
    implicit val k = kryo
    Codec.put(i)
    val r = b.result()
    implicit val it = r.iterator
    val out = Codec.get()
    out should be(i)
    maybeValidateLen foreach (l => (r.size - 1) should equal(l))
  }

  private def validateAll(is: Any*) = is.foreach(validate(_, None))

  "Codec" should "correctly convert Byte" in {
    for {i <- Byte.MinValue.toInt to Byte.MaxValue.toInt} validate(i.toByte, Some(1))
  }
  it should "correctly convert Short" in {
    def expectedLen(i: Int) = i match {
      case x if ((0xFF >> 2) & x) == x => 1
      case x if ((0xFFFF >> 2) & x) == x => 2
      case _ => 3
    }
    validate(Short.MinValue, Some(expectedLen(Short.MinValue)))
    validate(Short.MaxValue, Some(expectedLen(Short.MaxValue)))
    validate(0.toShort, Some(expectedLen(0)))
    validate(1.toShort, Some(expectedLen(1)))
    validate(-1.toShort, Some(expectedLen(-1)))
    validate(5.toShort, Some(expectedLen(5)))
    validate(-5.toShort, Some(expectedLen(-5)))
    for {i <- Short.MinValue.toInt to Short.MaxValue.toInt} validate(i.toShort, Some(expectedLen(i)))
  }
  it should "correctly convert Int" in {
    def expectedLen(i: Int) = i match {
      case x if ((0xFF >> 3) & x) == x => 1
      case x if ((0xFFFF >> 3) & x) == x => 2
      case x if ((0xFFFFFF >> 3) & x) == x => 3
      case x if ((0xFFFFFFFFL >> 3) & x) == x => 4
      case _ => 5
    }

    validate(Int.MinValue, Some(expectedLen(Int.MinValue)))
    validate(Int.MaxValue, Some(expectedLen(Int.MaxValue)))
    validate(0, Some(expectedLen(0)))
    validate(1, Some(expectedLen(1)))
    validate(-1, Some(expectedLen(-1)))
    validate(5, Some(expectedLen(5)))
    validate(-5, Some(expectedLen(-5)))
    for {i <- Int.MinValue to Int.MaxValue by 2000} validate(i.toInt, Some(expectedLen(i)))
  }

  it should "correctly convert Long" in {
    def expectedLen(i: Long) = i match {
      case x if ((0xFF >> 4) & x) == x => 1
      case x if ((0xFFFF >> 4) & x) == x => 2
      case x if ((0xFFFFFFL >> 4) & x) == x => 3
      case x if ((0xFFFFFFFFL >> 4) & x) == x => 4
      case x if ((0xFFFFFFFFFFL >> 4) & x) == x => 5
      case x if ((0xFFFFFFFFFFFFL >> 4) & x) == x => 6
      case x if ((0xFFFFFFFFFFFFFFL >> 4) & x) == x => 7
      case x if (0xFFFFFFFFFFFFFFFL & x) == x => 8
      case _ => 9
    }

    validate(Long.MinValue, Some(expectedLen(Long.MinValue)))
    validate(Long.MaxValue, Some(expectedLen(Long.MaxValue)))
    validate(0.toLong, Some(expectedLen(0)))
    validate(1.toLong, Some(expectedLen(1)))
    validate(-1.toLong, Some(expectedLen(-1)))
    validate(5.toLong, Some(expectedLen(5)))
    validate(-5.toLong, Some(expectedLen(-5)))
    for {i <- Long.MinValue to Long.MaxValue by 7300000000001L} validate(i.toLong, Some(expectedLen(i)))
  }

  it should "correctly convert Float" in {
    for {i <- Array(0, 1, -1, 5, -5, 1.01, 1.1112, -1.2223, 12345678, -19299346.99)} validate(i.toFloat, None)
  }

  it should "correctly convert Double" in {
    for {i <- Array[Double](Double.MaxValue, Double.MinValue, 0, 1, -1, 5, -5, 1.01, 1.1112, -1.2223, 12345678, -19299383746.99)} validate(i.toDouble, None)
  }

  it should "correctly convert Boolean" in {
    for {i <- Array[Boolean](true, false)} validate(i, Some(1))
  }

  it should "correctly convert Char" in {
    for {i <- Array[Char]('A', '1', 'a')} validate(i, Some(1))
  }

  it should "correctly convert Array[Byte]" in {
    validateAll(Array(0), Array(), Array(0, 1), Array(-10, 2, 10, 30))
  }
  it should "correctly convert Array[Long]" in {
    validateAll(Array(0L), Array(), Array(0L, 1L), Array(-10L, 2L, 10L, Long.MaxValue, Long.MinValue))
  }
  it should "correctly convert Array[Any]" in {
    validateAll(Array(0), Array(), Array(0, 'C'), Array(-10L, 2.02, 10L, Int.MaxValue, Long.MinValue))
  }
  it should "correctly convert List[Any]" in {
    validateAll(List(), List(1), List(1, 2, 'C'))
  }
  it should "correctly convert Option[Any]" in {
    validateAll(None, Some(123), Some(true), Some(List(1, 2, 'C')))
  }
  it should "correctly convert String" in {
    validateAll("", "Hello", "на русском", "123-37^&@^\n\r\t\u2202")
  }
  it should "correctly convert Acknowledgement" in {
    validateAll(
      Acknowledgement(SequentialMessageId("seed", 12345L)),
      Acknowledgement(RandomStringMessageId()),
      Acknowledgement(LongMessageId(1234))
    )
  }

  private def someSubject = Subject(ServiceKey("sid"), TopicKey("tid"), "some tags")

  private def someStreamId = CompoundStreamId("a", "b")

  it should "correctly convert StreamMapping" in {
    validateAll(
      StreamMapping(someSubject, Some(SimpleStreamId("bla"))),
      StreamMapping(someSubject, Some(CompoundStreamId("a", 127))),
      StreamMapping(someSubject, Some(CompoundStreamId("a", 127L))),
      StreamMapping(someSubject, Some(CompoundStreamId("a", 127D))),
      StreamMapping(someSubject, Some(CompoundStreamId("a", "b"))),
      StreamMapping(someSubject, None)
    )
  }
  it should "correctly convert GetMappingFor" in {
    validateAll(GetMappingFor(someSubject))
  }
  it should "correctly convert StreamUpdate" in {
    validateAll(
      StreamUpdate(someStreamId, StringStreamState("str")),
      StreamUpdate(someStreamId, DictionaryMapStreamState(1, 123, Array(1, "a", 2.1D), Dictionary("f1", "f2", "f3"))),
      StreamUpdate(someStreamId, DictionaryMapStreamState(1, 123, Array(1, "a", 2.1D), Dictionary("f1", "f2", "f3")).transitionTo(Array(2, "b", 2.1D))),
      StreamUpdate(someStreamId, ListStreamState(1, 123, List(1, 2), ListSpecs(10, FromHead), List())),
      StreamUpdate(someStreamId, ListStreamState(1, 123, List(1, 2), ListSpecs(10, FromHead), List(ListStreamState.Add(-1, 2)))
        .transitionFrom(Some(ListStreamState(1, 122, List(1), ListSpecs(10, FromHead), List()))).get),
      StreamUpdate(someStreamId, SetStreamState(1, 123, Set(1, 2), SetSpecs(allowPartialUpdates = true))),
      StreamUpdate(someStreamId, SetStreamState(1, 123, Set(1, 2), SetSpecs(allowPartialUpdates = true))
        .transitionFrom(Some(SetStreamState(1, 122, Set(1, 3), SetSpecs(allowPartialUpdates = true)))).get)
    )
  }
  it should "correctly convert SignalPayload" in {
    validateAll(SignalPayload(someSubject, "Payload", 123L, Some("bla")))
  }

  it should "correctly convert SignalAckOk" in {
    validateAll(SignalAckOk(Some("bla"), someSubject, Some("payload")))
  }
  it should "correctly convert SignalAckFailed" in {
    validateAll(SignalAckFailed(Some("bla"), someSubject, Some("payload")))
  }
  it should "correctly convert DownstreamDemandRequest" in {
    validateAll(DownstreamDemandRequest(RandomStringMessageId(), 123))
  }
  it should "correctly convert CloseStreamFor" in {
    validateAll(CloseStreamFor(someStreamId))
  }
  it should "correctly convert OpenStreamFor" in {
    validateAll(OpenStreamFor(someStreamId))
  }
  it should "correctly convert StreamResyncRequest" in {
    validateAll(StreamResyncRequest(someStreamId))
  }

}
