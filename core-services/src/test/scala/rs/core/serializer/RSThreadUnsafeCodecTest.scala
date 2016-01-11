package rs.core.serializer

import akka.actor.ActorRef
import akka.util.ByteString
import org.scalatest.{FlatSpec, Matchers}
import rs.core.serializer.RSThreadUnsafeCodec._

class RSThreadUnsafeCodecTest extends FlatSpec with Matchers {

  implicit val ec: ExternalCodecs = new ExternalCodecs {
    override def resolveActorRef(s: String): ActorRef = null
  }

  def builder = ByteString.newBuilder


  "EntityType" should "convert correctly for all valid values" in {
    for {i <- 0 to 32767} {
      implicit val b = builder
      EntityType.put(i)
      implicit val it = b.result().iterator
      EntityType.get() should equal(i)
    }
  }

  it should "fail for all illegal values" in {
    for {i <- 32768 to 40000} {
      implicit val b = builder
      an[Exception] should be thrownBy EntityType.put(i)
    }
  }

  it should "have expected length when encoded" in {
    for {i <- 0 to 127} {
      implicit val b = builder
      EntityType.put(i)
      b.result().size should equal(1)
    }
    for {i <- 128 to 32767} {
      implicit val b = builder
      EntityType.put(i)
      b.result().size should equal(2)
    }
  }

  private def validate(i: Any, maybeValidateLen: Option[Int]): Unit = {
    implicit val b = builder
    Codec.put(i)
    val r = b.result()
    implicit val it = r.iterator
    val out = Codec.get()
    out should be(i)
    out should equal(i)
    maybeValidateLen foreach (l => (r.size - 1) should equal(l))
  }

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
    for {i <- Array[Any](Array(0), Array(), Array(0, 1), Array(-10, 2, 10, 30))} validate(i, None)
  }
  it should "correctly convert Array[Long]" in {
    for {i <- Array[Any](Array(0L), Array(), Array(0L, 1L), Array(-10L, 2L, 10L, Long.MaxValue, Long.MinValue))} validate(i, None)
  }
  it should "correctly convert Array[Any]" in {
    for {i <- Array[Any](Array(0), Array(), Array(0, 'C'), Array(-10L, 2.02, 10L, Int.MaxValue, Long.MinValue))} validate(i, None)
  }
  it should "correctly convert List[Any]" in {
    for {i <- Array[Any](List(), List(1), List(1, 2, 'C'))} validate(i, None)
  }
  it should "correctly convert Option[Any]" in {
    for {i <- Array[Any](None, Some(123), Some(true), Some(List(1, 2, 'C')))} validate(i, None)
  }
  it should "correctly convert String" in {
    for {i <- Array[Any]("", "Hello", "?? ???????", "123-37^&@^\n\r\t\u2202")} validate(i, None)
  }

}
