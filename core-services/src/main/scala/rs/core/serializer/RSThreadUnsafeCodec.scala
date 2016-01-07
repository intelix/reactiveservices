package rs.core.serializer

import java.nio.ByteOrder

import akka.actor.ActorRef
import akka.serialization.Serialization
import akka.util.{ByteString, ByteIterator, ByteStringBuilder}
import rs.core.serializer.RSThreadUnsafeCodec.ExternalCodecs
import rs.core.services.BaseServiceActor._
import rs.core.services.Messages.{SignalAckFailed, SignalAckOk}
import rs.core.services._
import rs.core.services.internal.InternalMessages.{DownstreamDemandRequest, SignalPayload, StreamUpdate}
import rs.core.services.internal.acks.{AcknowledgeableWithSpecificId, Acknowledgement}
import rs.core.stream.DictionaryMapStreamState.Dictionary
import rs.core.stream._
import rs.core.{ServiceKey, Subject, TopicKey}


private[serializer] object RSThreadUnsafeCodec {

  trait ExternalCodecs {
    def resolveActorRef(s: String): ActorRef
  }

  implicit val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

  trait Sx[T] {
    def put(v: T)(implicit b: ByteStringBuilder): Unit

    def get()(implicit b: ByteIterator, ec: ExternalCodecs): T
  }

  trait S[T] extends Sx[T] {
    val id: Int

    def putWithType(v: T)(implicit b: ByteStringBuilder): Unit = {
      EntityType.put(id)
      put(v)
    }
  }

  object ByteC extends S[Byte] {
    override val id: Int = 0

    override def put(v: Byte)(implicit b: ByteStringBuilder): Unit = b.putByte(v)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Byte = b.getByte

  }

  object ShortC extends S[Short] {
    val ControlBits = 2
    val RemainderMask = (1 << ControlBits) - 1
    val ControlBitsMask = RemainderMask << (8 - ControlBits)
    val B1 = 0xFF >> ControlBits
    val B2 = (B1 << 8) | 0xFF
    val B2i = 1 << (8 - ControlBits)
    val B3i = 2 << (8 - ControlBits)
    override val id: Int = 2

    override def put(v: Short)(implicit b: ByteStringBuilder): Unit =
      v match {
        case _ if (v & B1) == v => b.putByte(v.toByte)
        case _ if (v & B2) == v =>
          b.putByte(((v >> 8) & B1 | B2i).toByte)
          b.putByte(v.toByte)
        case _ =>
          b.putByte(((v >> (8 + ControlBits)) & B1 | B3i).toByte)
          b.putByte(((v >> ControlBits) & 0xFF).toByte)
          b.putByte((v & RemainderMask).toByte)
      }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Short = b.getByte match {
      case v if (v & ControlBitsMask) == 0 => (v & 0xFF).toShort
      case v if (v & ControlBitsMask) == B2i => (((v & B1) << 8) | (b.getByte & 0xFF)).toShort
      case v => (((v & B1) << (8 + ControlBits)) | ((b.getByte & 0xFF) << ControlBits) | (b.getByte & RemainderMask)).toShort
    }

  }

  object IntC extends S[Int] {
    val ControlBits = 3
    val RemainderMask = (1 << ControlBits) - 1
    val ControlBitsMask = RemainderMask << (8 - ControlBits)
    val B1 = 0xFF >> ControlBits
    val B2 = (B1 << 8) | 0xFF
    val B3 = (B2 << 8) | 0xFF
    val B4 = (B3 << 8) | 0xFF
    val B2i = 1 << (8 - ControlBits)
    val B3i = 2 << (8 - ControlBits)
    val B4i = 3 << (8 - ControlBits)
    val B5i = 4 << (8 - ControlBits)

    override val id: Int = 3

    override def put(v: Int)(implicit b: ByteStringBuilder): Unit =
      v match {
        case _ if (v & B1) == v => b.putByte(v.toByte)
        case _ if (v & B2) == v =>
          b.putByte(((v >> 8) & B1 | B2i).toByte)
          b.putByte(v.toByte)
        case _ if (v & B3) == v =>
          b.putByte(((v >> 16) & B1 | B3i).toByte)
          b.putByte((v >> 8).toByte)
          b.putByte(v.toByte)
        case _ if (v & B4) == v =>
          b.putByte(((v >> 24) & B1 | B4i).toByte)
          b.putByte((v >> 16).toByte)
          b.putByte((v >> 8).toByte)
          b.putByte(v.toByte)
        case _ =>
          b.putByte(((v >> (24 + ControlBits)) & B1 | B5i).toByte)
          b.putByte((v >> (16 + ControlBits)).toByte)
          b.putByte((v >> (8 + ControlBits)).toByte)
          b.putByte(((v >> ControlBits) & 0xFF).toByte)
          b.putByte((v & RemainderMask).toByte)
      }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Int = b.getByte match {
      case v if (v & ControlBitsMask) == 0 => v & 0xFF
      case v if (v & ControlBitsMask) == B2i => ((v & B1) << 8) | (b.getByte & 0xFF)
      case v if (v & ControlBitsMask) == B3i => ((v & B1) << 16) | ((b.getByte & 0xFF) << 8) | (b.getByte & 0xFF)
      case v if (v & ControlBitsMask) == B4i => ((v & B1) << 24) | ((b.getByte & 0xFF) << 16) | ((b.getByte & 0xFF) << 8) | (b.getByte & 0xFF)
      case v => ((v & B1) << (24 + ControlBits)) |
        ((b.getByte & 0xFF) << (16 + ControlBits)) |
        ((b.getByte & 0xFF) << (8 + ControlBits)) |
        ((b.getByte & 0xFF) << ControlBits) |
        (b.getByte & RemainderMask)
    }

  }

  object LongC extends S[Long] {
    val ControlBits = 4
    val RemainderMask = (1 << ControlBits) - 1
    val ControlBitsMask = RemainderMask << (8 - ControlBits)
    val B1 = 0xFF >> ControlBits
    val B2 = (B1 << 8).toLong | 0xFF
    val B3 = (B2 << 8) | 0xFF
    val B4 = (B3 << 8) | 0xFF
    val B5 = (B4 << 8) | 0xFF
    val B6 = (B5 << 8) | 0xFF
    val B7 = (B6 << 8) | 0xFF
    val B8 = (B7 << 8) | 0xFF
    val B2i = 1 << (8 - ControlBits)
    val B3i = 2 << (8 - ControlBits)
    val B4i = 3 << (8 - ControlBits)
    val B5i = 4 << (8 - ControlBits)
    val B6i = 5 << (8 - ControlBits)
    val B7i = 6 << (8 - ControlBits)
    val B8i = 7 << (8 - ControlBits)
    val B9i = 8 << (8 - ControlBits)

    override val id: Int = 4

    override def put(v: Long)(implicit b: ByteStringBuilder): Unit =
      v match {
        case _ if (v & B1) == v => b.putByte(v.toByte)
        case _ if (v & B2) == v =>
          b.putByte(((v >> 8) & B1 | B2i).toByte)
          b.putByte(v.toByte)
        case _ if (v & B3) == v =>
          b.putByte(((v >> 16) & B1 | B3i).toByte)
          b.putByte((v >> 8).toByte)
          b.putByte(v.toByte)
        case _ if (v & B4) == v =>
          b.putByte(((v >> 24) & B1 | B4i).toByte)
          b.putByte((v >> 16).toByte)
          b.putByte((v >> 8).toByte)
          b.putByte(v.toByte)
        case _ if (v & B5) == v =>
          b.putByte(((v >> 32) & B1 | B5i).toByte)
          b.putByte((v >> 24).toByte)
          b.putByte((v >> 16).toByte)
          b.putByte((v >> 8).toByte)
          b.putByte(v.toByte)
        case _ if (v & B6) == v =>
          b.putByte(((v >> 40) & B1 | B6i).toByte)
          b.putByte((v >> 32).toByte)
          b.putByte((v >> 24).toByte)
          b.putByte((v >> 16).toByte)
          b.putByte((v >> 8).toByte)
          b.putByte(v.toByte)
        case _ if (v & B7) == v =>
          b.putByte(((v >> 48) & B1 | B7i).toByte)
          b.putByte((v >> 40).toByte)
          b.putByte((v >> 32).toByte)
          b.putByte((v >> 24).toByte)
          b.putByte((v >> 16).toByte)
          b.putByte((v >> 8).toByte)
          b.putByte(v.toByte)
        case _ if (v & B8) == v =>
          b.putByte(((v >> 56) & B1 | B8i).toByte)
          b.putByte((v >> 48).toByte)
          b.putByte((v >> 40).toByte)
          b.putByte((v >> 32).toByte)
          b.putByte((v >> 24).toByte)
          b.putByte((v >> 16).toByte)
          b.putByte((v >> 8).toByte)
          b.putByte(v.toByte)
        case _ =>
          b.putByte(((v >> (56 + ControlBits)) & B1 | B9i).toByte)
          b.putByte((v >> (48 + ControlBits)).toByte)
          b.putByte((v >> (40 + ControlBits)).toByte)
          b.putByte((v >> (32 + ControlBits)).toByte)
          b.putByte((v >> (24 + ControlBits)).toByte)
          b.putByte((v >> (16 + ControlBits)).toByte)
          b.putByte((v >> (8 + ControlBits)).toByte)
          b.putByte(((v >> ControlBits) & 0xFF).toByte)
          b.putByte((v & RemainderMask).toByte)
      }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Long = b.getByte match {
      case v if (v & ControlBitsMask) == 0 => (v & 0xFF).toLong
      case v if (v & ControlBitsMask) == B2i => ((v & B1).toLong << 8) | (b.getByte & 0xFF)
      case v if (v & ControlBitsMask) == B3i => ((v & B1).toLong << 16) | ((b.getByte & 0xFF) << 8) | (b.getByte & 0xFF)
      case v if (v & ControlBitsMask) == B4i => ((v & B1).toLong << 24) | ((b.getByte & 0xFF) << 16) | ((b.getByte & 0xFF) << 8) | (b.getByte & 0xFF)
      case v if (v & ControlBitsMask) == B5i =>
        ((v & B1).toLong << 32) |
          ((b.getByte & 0xFF).toLong << 24) |
          ((b.getByte & 0xFF) << 16) |
          ((b.getByte & 0xFF) << 8) |
          (b.getByte & 0xFF)

      case v if (v & ControlBitsMask) == B6i =>
        ((v & B1).toLong << 40) |
          ((b.getByte & 0xFF).toLong << 32) |
          ((b.getByte & 0xFF).toLong << 24) |
          ((b.getByte & 0xFF) << 16) |
          ((b.getByte & 0xFF) << 8) |
          (b.getByte & 0xFF)
      case v if (v & ControlBitsMask) == B7i =>
        ((v & B1).toLong << 48) |
          ((b.getByte & 0xFF).toLong << 40) |
          ((b.getByte & 0xFF).toLong << 32) |
          ((b.getByte & 0xFF).toLong << 24) |
          ((b.getByte & 0xFF) << 16) |
          ((b.getByte & 0xFF) << 8) |
          (b.getByte & 0xFF)
      case v if (v & ControlBitsMask) == B8i =>
        ((v & B1).toLong << 56) |
          ((b.getByte & 0xFF).toLong << 48) |
          ((b.getByte & 0xFF).toLong << 40) |
          ((b.getByte & 0xFF).toLong << 32) |
          ((b.getByte & 0xFF).toLong << 24) |
          ((b.getByte & 0xFF) << 16) |
          ((b.getByte & 0xFF) << 8) |
          (b.getByte & 0xFF)
      case v => ((v & B1).toLong << (56 + ControlBits)) |
        ((b.getByte & 0xFF).toLong << (48 + ControlBits)) |
        ((b.getByte & 0xFF).toLong << (40 + ControlBits)) |
        ((b.getByte & 0xFF).toLong << (32 + ControlBits)) |
        ((b.getByte & 0xFF).toLong << (24 + ControlBits)) |
        ((b.getByte & 0xFF) << (16 + ControlBits)) |
        ((b.getByte & 0xFF) << (8 + ControlBits)) |
        ((b.getByte & 0xFF) << ControlBits) |
        (b.getByte & RemainderMask)
    }

  }

  object FloatC extends S[Float] {

    override val id: Int = 5

    override def put(v: Float)(implicit b: ByteStringBuilder): Unit = b.putFloat(v)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Float = b.getFloat

  }

  object DoubleC extends S[Double] {
    override val id: Int = 6

    override def put(v: Double)(implicit b: ByteStringBuilder): Unit = b.putDouble(v)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Double = b.getDouble

  }

  object CharC extends S[Char] {
    override val id: Int = 7

    override def put(v: Char)(implicit b: ByteStringBuilder): Unit = b.putByte(v.toByte)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Char = b.getByte.toChar

  }

  object BooleanC extends S[Boolean] {
    override val id: Int = 8

    override def put(v: Boolean)(implicit b: ByteStringBuilder): Unit = b.putByte(if (v) 1 else 0)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Boolean = if (b.getByte == 0) false else true

  }

  object ArrayOfAnyC extends S[Array[_]] {
    override val id: Int = 9

    override def put(v: Array[_])(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.length)
      var idx = 0
      while (idx < v.length) {
        Codec.put(v(idx))
        idx += 1
      }
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Array[Any] = {
      val len = IntC.get()
      val arr = Array.ofDim[Any](len)
      var idx = 0
      while (idx < len) {
        arr(idx) = Codec.get()
        idx += 1
      }
      arr
    }

  }

  object ArrayOfStringC extends Sx[Array[String]] {

    override def put(v: Array[String])(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.length)
      var idx = 0
      while (idx < v.length) {
        StringC.put(v(idx))
        idx += 1
      }
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Array[String] = {
      val len = IntC.get()
      val arr = Array.ofDim[String](len)
      var idx = 0
      while (idx < len) {
        arr(idx) = StringC.get()
        idx += 1
      }
      arr
    }

  }


  object OptionOfAnyC extends S[Option[_]] {
    override val id: Int = 10

    override def put(v: Option[_])(implicit b: ByteStringBuilder): Unit = v match {
      case None => b.putByte(0)
      case Some(x) => b.putByte(1); Codec.put(x)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Option[_] = b.getByte match {
      case 0 => None
      case 1 => Some(Codec.get())
    }

  }

  object OptionOfStringC extends Sx[Option[String]] {

    override def put(v: Option[String])(implicit b: ByteStringBuilder): Unit = v match {
      case None => b.putByte(0)
      case Some(x) => b.putByte(1); StringC.put(x)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Option[String] = b.getByte match {
      case 0 => None
      case 1 => Some(StringC.get())
    }

  }

  object OptionOfIntC extends Sx[Option[Int]] {

    override def put(v: Option[Int])(implicit b: ByteStringBuilder): Unit = v match {
      case None => b.putByte(0)
      case Some(x) => b.putByte(1); IntC.put(x)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Option[Int] = b.getByte match {
      case 0 => None
      case 1 => Some(IntC.get())
    }

  }


  object ListOfAnyC extends S[List[_]] {
    override val id: Int = 11

    override def put(v: List[_])(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.size)
      v foreach Codec.put
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): List[Any] = {
      val len = IntC.get()
      var arr = List[Any]()
      for (_ <- 1 to len) arr = Codec.get +: arr
      arr.reverse
    }

  }

  object SeqOfAnyC extends Sx[Seq[_]] {

    override def put(v: Seq[_])(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.size)
      v foreach Codec.put
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Seq[Any] = {
      val len = IntC.get()
      var arr = Seq[Any]()
      for (_ <- 1 to len) arr = Codec.get +: arr
      arr.reverse
    }

  }

  object SetOfAnyC extends Sx[Set[_]] {

    override def put(v: Set[_])(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.size)
      v foreach Codec.put
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Set[Any] = {
      val len = IntC.get()
      var arr = Set[Any]()
      for (_ <- 1 to len) arr = arr + Codec.get
      arr
    }

  }


  object StringC extends S[String] {
    override val id: Int = 12

    override def put(v: String)(implicit b: ByteStringBuilder): Unit = {
      val bytes = v.getBytes("UTF-8")
      IntC.put(bytes.length)
      b.putBytes(bytes)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): String = {
      val len = IntC.get()
      new String(b.getBytes(len), "UTF-8")
    }

  }


  object ActorRefC extends S[ActorRef] {
    override val id: Int = 13

    override def put(v: ActorRef)(implicit b: ByteStringBuilder): Unit = {
      StringC.put(Serialization.serializedActorPath(v))
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): ActorRef =
      ec.resolveActorRef(StringC.get())

  }

  object AcknowledgeableWithSpecificIdC extends S[AcknowledgeableWithSpecificId] {
    override val id: Int = 14

    override def put(v: AcknowledgeableWithSpecificId)(implicit b: ByteStringBuilder): Unit = {
      Codec.put(v.payload)
      OptionOfAnyC.put(v.acknowledgeTo)
      Codec.put(v.messageId)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): AcknowledgeableWithSpecificId = {
      AcknowledgeableWithSpecificId(Codec.get(), OptionOfAnyC.get().asInstanceOf[Option[ActorRef]], Codec.get().asInstanceOf[MessageId])
    }
  }

  object AcknowledgementC extends S[Acknowledgement] {
    override val id: Int = 15

    override def put(v: Acknowledgement)(implicit b: ByteStringBuilder): Unit = Codec.put(v.messageId)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Acknowledgement = Acknowledgement(Codec.get().asInstanceOf[MessageId])
  }


  object GetMappingForC extends S[GetMappingFor] {
    override val id: Int = 16

    override def put(v: GetMappingFor)(implicit b: ByteStringBuilder): Unit = SubjectC.put(v.subj)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): GetMappingFor = GetMappingFor(SubjectC.get())
  }

  object ServiceEndpointC extends S[ServiceEndpoint] {
    override val id: Int = 17

    override def put(v: ServiceEndpoint)(implicit b: ByteStringBuilder): Unit = {
      ActorRefC.put(v.ref)
      StringC.put(v.id)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): ServiceEndpoint = ServiceEndpoint(ActorRefC.get(), StringC.get())
  }

  object CloseStreamForC extends S[CloseStreamFor] {
    override val id: Int = 18

    override def put(v: CloseStreamFor)(implicit b: ByteStringBuilder): Unit = Codec.put(v.streamKey)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): CloseStreamFor = CloseStreamFor(Codec.get().asInstanceOf[StreamId])
  }

  object OpenStreamForC extends S[OpenStreamFor] {
    override val id: Int = 19

    override def put(v: OpenStreamFor)(implicit b: ByteStringBuilder): Unit = Codec.put(v.streamKey)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): OpenStreamFor = OpenStreamFor(Codec.get().asInstanceOf[StreamId])
  }

  object StreamResyncRequestC extends S[StreamResyncRequest] {
    override val id: Int = 20

    override def put(v: StreamResyncRequest)(implicit b: ByteStringBuilder): Unit = Codec.put(v.streamKey)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): StreamResyncRequest = StreamResyncRequest(Codec.get().asInstanceOf[StreamId])
  }

  object StreamMappingC extends S[StreamMapping] {
    override val id: Int = 21

    override def put(v: StreamMapping)(implicit b: ByteStringBuilder): Unit = {
      SubjectC.put(v.subj)
      OptionOfAnyC.put(v.mappedStreamKey)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): StreamMapping =
      StreamMapping(SubjectC.get(), OptionOfAnyC.get().asInstanceOf[Option[StreamId]])
  }


  object StreamUpdateC extends S[StreamUpdate] {
    override val id: Int = 22

    override def put(v: StreamUpdate)(implicit b: ByteStringBuilder): Unit = {
      Codec.put(v.key)
      Codec.put(v.tran)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): StreamUpdate =
      StreamUpdate(Codec.get().asInstanceOf[StreamId], Codec.get().asInstanceOf[StreamStateTransition])
  }

  object SignalPayloadC extends S[SignalPayload] {
    override val id: Int = 23

    override def put(v: SignalPayload)(implicit b: ByteStringBuilder): Unit = {
      SubjectC.put(v.subj)
      Codec.put(v.payload)
      LongC.put(v.expireAt)
      OptionOfAnyC.put(v.correlationId)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): SignalPayload =
      SignalPayload(SubjectC.get(), Codec.get(), LongC.get(), OptionOfAnyC.get())
  }

  object SignalAckOkC extends S[SignalAckOk] {
    override val id: Int = 24

    override def put(v: SignalAckOk)(implicit b: ByteStringBuilder): Unit = {
      OptionOfAnyC.put(v.correlationId)
      SubjectC.put(v.subj)
      OptionOfAnyC.put(v.payload)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): SignalAckOk =
      SignalAckOk(OptionOfAnyC.get(), SubjectC.get(), OptionOfAnyC.get())
  }

  object SignalAckFailedC extends S[SignalAckFailed] {
    override val id: Int = 25

    override def put(v: SignalAckFailed)(implicit b: ByteStringBuilder): Unit = {
      OptionOfAnyC.put(v.correlationId)
      SubjectC.put(v.subj)
      OptionOfAnyC.put(v.payload)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): SignalAckFailed =
      SignalAckFailed(OptionOfAnyC.get(), SubjectC.get(), OptionOfAnyC.get())
  }

  object DownstreamDemandRequestC extends S[DownstreamDemandRequest] {
    override val id: Int = 26

    override def put(v: DownstreamDemandRequest)(implicit b: ByteStringBuilder): Unit = {
      Codec.put(v.messageId)
      LongC.put(v.count)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): DownstreamDemandRequest =
      DownstreamDemandRequest(Codec.get().asInstanceOf[MessageId], LongC.get())
  }

  object SequentialMessageIdC extends S[SequentialMessageId] {
    override val id: Int = 27

    override def put(v: SequentialMessageId)(implicit b: ByteStringBuilder): Unit = {
      StringC.put(v.seed)
      LongC.put(v.sequence)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): SequentialMessageId = SequentialMessageId(StringC.get(), LongC.get())
  }

  object RandomStringMessageIdC extends S[RandomStringMessageId] {
    override val id: Int = 28

    override def put(v: RandomStringMessageId)(implicit b: ByteStringBuilder): Unit = StringC.put(v.id)


    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): RandomStringMessageId = RandomStringMessageId(StringC.get())
  }

  object LongMessageIdC extends S[LongMessageId] {
    override val id: Int = 29

    override def put(v: LongMessageId)(implicit b: ByteStringBuilder): Unit = LongC.put(v.id)


    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): LongMessageId = LongMessageId(LongC.get())
  }

  object SimpleStreamIdC extends S[SimpleStreamId] {
    override val id: Int = 30

    override def put(v: SimpleStreamId)(implicit b: ByteStringBuilder): Unit = StringC.put(v.id)


    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): SimpleStreamId = SimpleStreamId(StringC.get())
  }

  object CompoundStreamIdC extends S[CompoundStreamId[_]] {
    override val id: Int = 31

    override def put(v: CompoundStreamId[_])(implicit b: ByteStringBuilder): Unit = {
      StringC.put(v.id)
      Codec.put(v.v)
    }


    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): CompoundStreamId[Any] = CompoundStreamId(StringC.get(), Codec.get())
  }


  object StringStreamStateC extends S[StringStreamState] {
    override val id: Int = 32

    override def put(v: StringStreamState)(implicit b: ByteStringBuilder): Unit = StringC.put(v.value)


    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): StringStreamState = StringStreamState(StringC.get())
  }

  object DictionaryMapStreamStateC extends S[DictionaryMapStreamState] {
    override val id: Int = 33

    override def put(v: DictionaryMapStreamState)(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.seed)
      IntC.put(v.seq)
      ArrayOfAnyC.put(v.values)
      ArrayOfStringC.put(v.dict.fields)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): DictionaryMapStreamState =
      DictionaryMapStreamState(IntC.get(), IntC.get(), ArrayOfAnyC.get(), Dictionary(ArrayOfStringC.get()))
  }

  object DictionaryMapStreamTransitionPartialC extends S[DictionaryMapStreamTransitionPartial] {
    override val id: Int = 34

    override def put(v: DictionaryMapStreamTransitionPartial)(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.seed)
      IntC.put(v.seq)
      IntC.put(v.seq2)
      ArrayOfAnyC.put(v.diffs)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): DictionaryMapStreamTransitionPartial =
      DictionaryMapStreamTransitionPartial(IntC.get(), IntC.get(), IntC.get(), ArrayOfAnyC.get())
  }

  object DictionaryMapStreamState_NoChangeC extends S[DictionaryMapStreamState.NoChange.type] {
    override val id: Int = 35

    override def put(v: DictionaryMapStreamState.NoChange.type)(implicit b: ByteStringBuilder): Unit = {}

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): DictionaryMapStreamState.NoChange.type =
      DictionaryMapStreamState.NoChange
  }


  object ListStreamStateC extends S[ListStreamState] {
    override val id: Int = 36

    override def put(v: ListStreamState)(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.seed)
      IntC.put(v.seq)
      ListOfAnyC.put(v.list)
      IntC.put(v.specs.max)
      v.specs.evictionStrategy match {
        case ListStreamState.RejectAdd => b.putByte(0)
        case ListStreamState.FromHead => b.putByte(1)
        case ListStreamState.FromTail => b.putByte(2)
      }
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): ListStreamState =
      ListStreamState(IntC.get(), IntC.get(), ListOfAnyC.get(), ListStreamState.ListSpecs(IntC.get(), b.getByte match {
        case 0 => ListStreamState.RejectAdd
        case 1 => ListStreamState.FromHead
        case 2 => ListStreamState.FromTail
      }), List.empty)
  }

  object ListStreamStateTransitionPartialC extends S[ListStreamStateTransitionPartial] {
    override val id: Int = 37

    override def put(v: ListStreamStateTransitionPartial)(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.seed)
      IntC.put(v.seq)
      IntC.put(v.seq2)
      ListOfAnyC.put(v.list)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): ListStreamStateTransitionPartial =
      ListStreamStateTransitionPartial(IntC.get(), IntC.get(), IntC.get(), ListOfAnyC.get().asInstanceOf[List[ListStreamState.Op]])
  }


  object ListStreamState_AddC extends S[ListStreamState.Add] {
    override val id: Int = 38

    override def put(v: ListStreamState.Add)(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.pos)
      Codec.put(v.v)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): ListStreamState.Add =
      ListStreamState.Add(IntC.get(), Codec.get())
  }

  object ListStreamState_ReplaceC extends S[ListStreamState.Replace] {
    override val id: Int = 39

    override def put(v: ListStreamState.Replace)(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.pos)
      Codec.put(v.v)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): ListStreamState.Replace =
      ListStreamState.Replace(IntC.get(), Codec.get())
  }

  object ListStreamState_RemoveC extends S[ListStreamState.Remove] {
    override val id: Int = 40

    override def put(v: ListStreamState.Remove)(implicit b: ByteStringBuilder): Unit = IntC.put(v.pos)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): ListStreamState.Remove =
      ListStreamState.Remove(IntC.get())
  }


  object SetStreamStateC extends S[SetStreamState] {
    override val id: Int = 41

    override def put(v: SetStreamState)(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.seed)
      IntC.put(v.seq)
      SetOfAnyC.put(v.set)
      BooleanC.put(v.specs.allowPartialUpdates)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): SetStreamState =
      SetStreamState(IntC.get(), IntC.get(), SetOfAnyC.get(), SetStreamState.SetSpecs(BooleanC.get()))
  }

  object SetStreamTransitionPartialC extends S[SetStreamTransitionPartial] {
    override val id: Int = 42

    override def put(v: SetStreamTransitionPartial)(implicit b: ByteStringBuilder): Unit = {
      IntC.put(v.seed)
      IntC.put(v.seq)
      IntC.put(v.seq2)
      SeqOfAnyC.put(v.list)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): SetStreamTransitionPartial =
      SetStreamTransitionPartial(IntC.get(), IntC.get(), IntC.get(), SeqOfAnyC.get().asInstanceOf[Seq[SetStreamState.SetOp]])
  }


  object SetStreamState_AddC extends S[SetStreamState.Add] {
    override val id: Int = 43

    override def put(v: SetStreamState.Add)(implicit b: ByteStringBuilder): Unit = {
      Codec.put(v.el)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): SetStreamState.Add =
      SetStreamState.Add(Codec.get())
  }

  object SetStreamState_RemoveC extends S[SetStreamState.Remove] {
    override val id: Int = 44

    override def put(v: SetStreamState.Remove)(implicit b: ByteStringBuilder): Unit = Codec.put(v.el)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): SetStreamState.Remove =
      SetStreamState.Remove(Codec.get())
  }


  object TopicKeyC extends S[TopicKey] {
    override val id: Int = 45
    override def put(v: TopicKey)(implicit b: ByteStringBuilder): Unit = StringC.put(v.id)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): TopicKey = TopicKey(StringC.get())
  }

  object ServiceKeyC extends S[ServiceKey] {
    override val id: Int = 46
    override def put(v: ServiceKey)(implicit b: ByteStringBuilder): Unit = StringC.put(v.id)

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): ServiceKey = ServiceKey(StringC.get())
  }

  object SubjectC extends S[Subject] {
    override val id: Int = 47
    override def put(v: Subject)(implicit b: ByteStringBuilder): Unit = {
      ServiceKeyC.put(v.service)
      TopicKeyC.put(v.topic)
      StringC.put(v.tags)
    }

    override def get()(implicit b: ByteIterator, ec: ExternalCodecs): Subject = Subject(ServiceKeyC.get(), TopicKeyC.get(), StringC.get())
  }





  object EntityType {
    val ReservedMask = 0xFF >> 1
    val NonReservedIndicator = 1 << 7

    def put(v: Int)(implicit b: ByteStringBuilder) =
      if (v <= ReservedMask) b.putByte(v.toByte)
      else {
        if (v > 32767) throw new RuntimeException(s"Illegal EntityType value: $v")
        b.putByte(((v >> 8) & ReservedMask | NonReservedIndicator).toByte)
        b.putByte(v.toByte)
      }

    def get()(implicit b: ByteIterator, ec: ExternalCodecs) = {
      val first = b.getByte
      val fr = first & ReservedMask
      if (fr == first) first.toInt else (fr << 8) | (b.getByte & 0xFF)
    }
  }


  object Codec {
    def put(v: Any)(implicit b: ByteStringBuilder) = v match {
      case x: Byte => ByteC.putWithType(x)
      case x: Short => ShortC.putWithType(x)
      case x: Int => IntC.putWithType(x)
      case x: Long => LongC.putWithType(x)
      case x: Float => FloatC.putWithType(x)
      case x: Double => DoubleC.putWithType(x)
      case x: Char => CharC.putWithType(x)
      case x: Boolean => BooleanC.putWithType(x)
      case x: Array[_] => ArrayOfAnyC.putWithType(x)
      case x: Option[_] => OptionOfAnyC.putWithType(x)
      case x: List[_] => ListOfAnyC.putWithType(x)
      case x: String => StringC.putWithType(x)
      case x: ActorRef => ActorRefC.putWithType(x)
      case x: AcknowledgeableWithSpecificId => AcknowledgeableWithSpecificIdC.putWithType(x)
      case x: Acknowledgement => AcknowledgementC.putWithType(x)
      case x: StreamMapping => StreamMappingC.putWithType(x)
      case x: GetMappingFor => GetMappingForC.putWithType(x)
      case x: ServiceEndpoint => ServiceEndpointC.putWithType(x)
      case x: StreamUpdate => StreamUpdateC.putWithType(x)
      case x: SignalPayload => SignalPayloadC.putWithType(x)
      case x: SignalAckOk => SignalAckOkC.putWithType(x)
      case x: SignalAckFailed => SignalAckFailedC.putWithType(x)
      case x: DownstreamDemandRequest => DownstreamDemandRequestC.putWithType(x)
      case x: SequentialMessageId => SequentialMessageIdC.putWithType(x)
      case x: RandomStringMessageId => RandomStringMessageIdC.putWithType(x)
      case x: LongMessageId => LongMessageIdC.putWithType(x)
      case x: SimpleStreamId => SimpleStreamIdC.putWithType(x)
      case x: CompoundStreamId[_] => CompoundStreamIdC.putWithType(x)
      case x: CloseStreamFor => CloseStreamForC.putWithType(x)
      case x: OpenStreamFor => OpenStreamForC.putWithType(x)
      case x: StreamResyncRequest => StreamResyncRequestC.putWithType(x)
      case x: StringStreamState => StringStreamStateC.putWithType(x)
      case x: DictionaryMapStreamState => DictionaryMapStreamStateC.putWithType(x)
      case x: DictionaryMapStreamTransitionPartial => DictionaryMapStreamTransitionPartialC.putWithType(x)
      case x: DictionaryMapStreamState.NoChange.type => DictionaryMapStreamState_NoChangeC.putWithType(x)
      case x: ListStreamState => ListStreamStateC.putWithType(x)
      case x: ListStreamStateTransitionPartial => ListStreamStateTransitionPartialC.putWithType(x)
      case x: ListStreamState.Add => ListStreamState_AddC.putWithType(x)
      case x: ListStreamState.Replace => ListStreamState_ReplaceC.putWithType(x)
      case x: ListStreamState.Remove => ListStreamState_RemoveC.putWithType(x)
      case x: SetStreamState => SetStreamStateC.putWithType(x)
      case x: SetStreamTransitionPartial => SetStreamTransitionPartialC.putWithType(x)
      case x: SetStreamState.Add => SetStreamState_AddC.putWithType(x)
      case x: SetStreamState.Remove => SetStreamState_RemoveC.putWithType(x)
      case x: TopicKey => TopicKeyC.putWithType(x)
      case x: ServiceKey => ServiceKeyC.putWithType(x)
      case x: Subject => SubjectC.putWithType(x)
    }

    def get()(implicit b: ByteIterator, ec: ExternalCodecs): Any = EntityType.get() match {
      case ByteC.id => ByteC.get()
      case ShortC.id => ShortC.get()
      case IntC.id => IntC.get()
      case LongC.id => LongC.get()
      case FloatC.id => FloatC.get()
      case DoubleC.id => DoubleC.get()
      case CharC.id => CharC.get()
      case BooleanC.id => BooleanC.get()
      case ArrayOfAnyC.id => ArrayOfAnyC.get()
      case OptionOfAnyC.id => OptionOfAnyC.get()
      case ListOfAnyC.id => ListOfAnyC.get()
      case StringC.id => StringC.get()
      case ActorRefC.id => ActorRefC.get()
      case AcknowledgeableWithSpecificIdC.id => AcknowledgeableWithSpecificIdC.get()
      case AcknowledgementC.id => AcknowledgementC.get()
      case StreamMappingC.id => StreamMappingC.get()
      case GetMappingForC.id => GetMappingForC.get()
      case ServiceEndpointC.id => ServiceEndpointC.get()
      case StreamUpdateC.id => StreamUpdateC.get()
      case SignalPayloadC.id => SignalPayloadC.get()
      case SignalAckOkC.id => SignalAckOkC.get()
      case SignalAckFailedC.id => SignalAckFailedC.get()
      case DownstreamDemandRequestC.id => DownstreamDemandRequestC.get()
      case SequentialMessageIdC.id => SequentialMessageIdC.get()
      case RandomStringMessageIdC.id => RandomStringMessageIdC.get()
      case LongMessageIdC.id => LongMessageIdC.get()
      case SimpleStreamIdC.id => SimpleStreamIdC.get()
      case CompoundStreamIdC.id => CompoundStreamIdC.get()
      case CloseStreamForC.id => CloseStreamForC.get()
      case OpenStreamForC.id => OpenStreamForC.get()
      case StreamResyncRequestC.id => StreamResyncRequestC.get()
      case StringStreamStateC.id => StringStreamStateC.get()
      case DictionaryMapStreamStateC.id => DictionaryMapStreamStateC.get()
      case DictionaryMapStreamTransitionPartialC.id => DictionaryMapStreamTransitionPartialC.get()
      case DictionaryMapStreamState_NoChangeC.id => DictionaryMapStreamState_NoChangeC.get()
      case ListStreamStateC.id => ListStreamStateC.get()
      case ListStreamStateTransitionPartialC.id => ListStreamStateTransitionPartialC.get()
      case ListStreamState_AddC.id => ListStreamState_AddC.get()
      case ListStreamState_ReplaceC.id => ListStreamState_ReplaceC.get()
      case ListStreamState_RemoveC.id => ListStreamState_RemoveC.get()
      case SetStreamStateC.id => SetStreamStateC.get()
      case SetStreamTransitionPartialC.id => SetStreamTransitionPartialC.get()
      case SetStreamState_AddC.id => SetStreamState_AddC.get()
      case SetStreamState_RemoveC.id => SetStreamState_RemoveC.get()
      case ServiceKeyC.id => ServiceKeyC.get()
      case TopicKeyC.id => TopicKeyC.get()
      case SubjectC.id => SubjectC.get()
    }
  }


}

private[serializer] class RSThreadUnsafeCodec {

  implicit val b = ByteString.newBuilder

  def encode(input: AnyRef): Array[Byte] = {
    b.clear()
    RSThreadUnsafeCodec.Codec.put(input)
    b.result().toArray
  }

  def decode(input: Array[Byte])(implicit ec: ExternalCodecs): AnyRef = {
    implicit val b = ByteString(input).iterator
    RSThreadUnsafeCodec.Codec.get().asInstanceOf[AnyRef]
  }

}
