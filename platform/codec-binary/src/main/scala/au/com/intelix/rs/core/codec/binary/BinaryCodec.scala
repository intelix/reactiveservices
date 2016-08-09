/*
 * Copyright 2014-16 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package au.com.intelix.rs.core.codec.binary

import java.nio.ByteOrder

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.{ByteIterator, ByteString, ByteStringBuilder}
import au.com.intelix.config.RootConfig
import au.com.intelix.evt.{EvtContext, TraceE}
import au.com.intelix.rs.core.codec.binary.BinaryCodec.Codecs.{ByteIdCodec, DefaultClientBinaryCodec, DefaultCommonDataBinaryCodec, DefaultServerBinaryCodec}
import au.com.intelix.rs.core.codec.binary.BinaryProtocolMessages._
import au.com.intelix.rs.core.config._
import au.com.intelix.rs.core.services.Messages._
import au.com.intelix.rs.core.stream.DictionaryMapStreamState.{Dictionary, NoChange}
import au.com.intelix.rs.core.stream.ListStreamState.{FromHead, FromTail, ListSpecs, RejectAdd}
import au.com.intelix.rs.core.stream.SetStreamState.{Add, Remove, SetOp, SetSpecs}
import au.com.intelix.rs.core.stream._
import au.com.intelix.rs.core.{ServiceKey, Subject, TopicKey}

import scala.annotation.tailrec


object BinaryCodec {

  object Evt {
    case object MessageEncoded extends TraceE
    case object MessageDecoded extends TraceE
  }

  object DefaultBinaryCodecImplicits {
    implicit val byteOrder = ByteOrder.BIG_ENDIAN
    implicit val idCodec = new ByteIdCodec
    implicit val commonCodec = new DefaultCommonDataBinaryCodec
    implicit val serverBinaryCodec = new DefaultServerBinaryCodec
    implicit val clientBinaryCodec: ClientBinaryCodec = new DefaultClientBinaryCodec
  }


  trait Codec[I, O] {
    def decode(bytesIterator: ByteIterator): I

    def encode(msg: O, builder: ByteStringBuilder): Unit
  }

  trait IdCodec extends Codec[Int, Int]

  trait ServerBinaryCodec extends Codec[BinaryDialectInbound, BinaryDialectOutbound]

  trait ClientBinaryCodec extends Codec[BinaryDialectOutbound, BinaryDialectInbound]

  trait CommonDataBinaryCodec extends Codec[Any, Any]


  object Streams {

    def serviceSideTranslator(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig): Graph[BidiShape[BinaryDialectInbound, ServiceInbound, ServiceOutbound, BinaryDialectOutbound], NotUsed] = new ServiceSideTranslator(sessionId, componentId)

    private class ServiceSideTranslator(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig) extends GraphStage[BidiShape[BinaryDialectInbound, ServiceInbound, ServiceOutbound, BinaryDialectOutbound]] {
      val in1: Inlet[BinaryDialectInbound] = Inlet("BytesIn")
      val out1: Outlet[ServiceInbound] = Outlet("ModelOut")
      val in2: Inlet[ServiceOutbound] = Inlet("ModelIn")
      val out2: Outlet[BinaryDialectOutbound] = Outlet("BytesOut")

      override val shape: BidiShape[BinaryDialectInbound, ServiceInbound, ServiceOutbound, BinaryDialectOutbound] = BidiShape(in1, out1, in2, out2)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
        var subjects: Map[Int, Subject] = Map()
        var aliases: Map[Subject, Int] = Map()

        var pendingToService: Option[ServiceInbound] = None
        var pendingToClient: Option[BinaryDialectOutbound] = None

        override def preStart(): Unit = {
          pull(in1)
          pull(in2)
        }

        setHandler(in1, new InHandler {
          override def onPush(): Unit =
            convert(grab(in1)) match {
              case b@Some(v) if !isAvailable(out1) => pendingToService = b
              case Some(v) => push(out1, v); pull(in1)
              case None => pull(in1)
            }
        })
        setHandler(out1, new OutHandler {
          override def onPull(): Unit = pendingToService foreach { next =>
            pull(in1)
            push(out1, next)
            pendingToService = None
          }
        })
        setHandler(in2, new InHandler {
          override def onPush(): Unit =
            convert(grab(in2)) match {
              case b@Some(v) if !isAvailable(out2) => pendingToClient = b
              case Some(v) => push(out2, v); pull(in2)
              case None => pull(in2)
            }
        })
        setHandler(out2, new OutHandler {
          override def onPull(): Unit = pendingToClient foreach { next =>
            pull(in2)
            push(out2, next)
            pendingToClient = None
          }
        })


        def convert(s: BinaryDialectInbound): Option[ServiceInbound] = s match {
          case m@BinaryDialectAlias(id, subj) =>
            subjects += id -> subj
            aliases += subj -> id
            None
          case t: BinaryDialectOpenSubscription => subjects get t.subjAlias map (OpenSubscription(_, t.priorityKey, t.aggregationIntervalMs))
          case t: BinaryDialectCloseSubscription => subjects get t.subjAlias map CloseSubscription
          case t: BinaryDialectSignal => subjects get t.subjAlias map (Signal(_, t.payload, t.expireAt, t.orderingGroup, t.correlationId))
          case _ => None
        }

        def convert(s: ServiceOutbound): Option[BinaryDialectOutbound] = s match {
          case t: InvalidRequest => aliases get t.subj map BinaryDialectInvalidRequest
          case t: ServiceNotAvailable => Some(BinaryDialectServiceNotAvailable(t.serviceKey))
          case t: StreamStateUpdate => aliases get t.subject map (BinaryDialectStreamStateUpdate(_, t.topicState))
          case t: SignalAckOk => aliases get t.subj map (BinaryDialectSignalAckOk(_, t.correlationId, t.payload))
          case t: SignalAckFailed => aliases get t.subj map (BinaryDialectSignalAckFailed(_, t.correlationId, t.payload))
          case t: SubscriptionClosed => aliases get t.subj map BinaryDialectSubscriptionClosed
          case _ => None
        }


      }

    }


    def buildServerSideTranslator(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig): BidiFlow[BinaryDialectInbound, ServiceInbound, ServiceOutbound, BinaryDialectOutbound, NotUsed] =
      BidiFlow.fromGraph(serviceSideTranslator(sessionId, componentId))


    def buildServerSideSerializer(sessionId: String, componentId: String)(implicit codec: ServerBinaryCodec, nodeCfg: RootConfig): BidiFlow[ByteString, BinaryDialectInbound, BinaryDialectOutbound, ByteString, NotUsed] =
      BidiFlow.fromGraph(GraphDSL.create() { b =>
        implicit val byteOrder = ByteOrder.BIG_ENDIAN

        val publisher = EvtContext("BinaryCodec", nodeCfg.config)

        val top = b add Flow[ByteString].mapConcat[BinaryDialectInbound] { x =>
          @tailrec def dec(l: List[BinaryDialectInbound], i: ByteIterator): List[BinaryDialectInbound] = if (!i.hasNext) l else dec(l :+ codec.decode(i), i)
          val i = x.iterator
          val decoded = dec(List.empty, i)
          publisher.raise(Evt.MessageDecoded, 'original -> x, 'decoded -> decoded)
          decoded
        }
        val bottom = b add Flow[BinaryDialectOutbound].map[ByteString] { x =>
          val b = ByteString.newBuilder // TODO - Can we reuse it
          codec.encode(x, b)
          val encoded = b.result()
          publisher.raise(Evt.MessageEncoded, 'original -> x, 'encoded -> encoded)
          encoded
        }
        BidiShape.fromFlows(top, bottom)
      })

    def buildClientSideSerializer()(implicit codec: ClientBinaryCodec): BidiFlow[ByteString, BinaryDialectOutbound, BinaryDialectInbound, ByteString, NotUsed] =
      BidiFlow.fromGraph(GraphDSL.create() { b =>
        implicit val byteOrder = ByteOrder.BIG_ENDIAN
        val top = b add Flow[ByteString].mapConcat[BinaryDialectOutbound] { x =>
          @tailrec def dec(l: List[BinaryDialectOutbound], i: ByteIterator): List[BinaryDialectOutbound] = if (!i.hasNext) l else dec(l :+ codec.decode(i), i)
          val i = x.iterator
          val decoded = dec(List.empty, i)
          decoded
        }
        val bottom = b add Flow[BinaryDialectInbound].map[ByteString] { x =>
          val b = ByteString.newBuilder
          codec.encode(x, b)
          val encoded = b.result()
          encoded
        }
        BidiShape.fromFlows(top, bottom)
      })


  }


  object Codecs {

    val TypeNone: Short = 0
    val TypeNil: Short = 1
    val TypeByte: Short = 2
    val TypeShort: Short = 3
    val TypeInt: Short = 4
    val TypeLong: Short = 5
    val TypeFloat: Short = 6
    val TypeDouble: Short = 7
    val TypeBoolean: Short = 8
    val TypeString: Short = 9

    val TypeServiceNotAvailable: Short = 20
    val TypeOpenSubscription: Short = 21
    val TypeCloseSubscription: Short = 22
    val TypeInvalidRequest: Short = 23
    val TypeStreamStateUpdate: Short = 24
    val TypeAlias: Short = 26
    val TypePing: Short = 27
    val TypePong: Short = 28
    val TypeStreamStateTransitionUpdate: Short = 29
    val TypeSignal: Short = 30
    val TypeSignalAckOk: Short = 31
    val TypeSignalAckFailed: Short = 32
    val TypeSubscriptionClosed: Short = 33
    val TypeResetSubscription: Short = 34


    val TypeStringStreamState: Short = 50

    val TypeDictionaryMapStreamState: Short = 52
    val TypeDictionaryMapNoChange: Short = 53
    val TypeDictionaryMapStreamTransitionPartial: Short = 54

    val TypeSetStreamState: Short = 55
    val TypeSetStreamTransitionPartial: Short = 56
    val TypeSetAddOp: Short = 57
    val TypeSetRemoveOp: Short = 58

    val TypeListStreamState: Short = 59
    val TypeListStreamTransitionPartial: Short = 60
    val TypeListAddOp: Short = 61
    val TypeListRemoveOp: Short = 62
    val TypeListReplaceOp: Short = 63


    object SubjectCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): Subject =
        Subject(ServiceKeyCodecLogic.decode(bytes), TopicKeyCodecLogic.decode(bytes))

      def encode(value: Subject, builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = {
        ServiceKeyCodecLogic.encode(value.service, builder)
        TopicKeyCodecLogic.encode(value.topic, builder)
      }
    }

    object ServiceKeyCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): ServiceKey = ServiceKey(StringCodecLogic.decode(bytes))

      def encode(value: ServiceKey, builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = StringCodecLogic.encode(value.id, builder)
    }


    object TopicKeyCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): TopicKey = TopicKey(StringCodecLogic.decode(bytes))

      def encode(value: TopicKey, builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = StringCodecLogic.encode(value.id, builder)
    }

    object StringCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): String = {
        val len = bytes.getInt
        val arr = Array.ofDim[Byte](len)
        bytes.getBytes(arr)
        new String(arr, "UTF-8")
      }

      def encode(value: String, builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = {
        val arr = value.getBytes("UTF-8")
        builder.putInt(arr.length)
        builder.putBytes(arr)
      }
    }

    object BooleanCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): Boolean = {
        bytes.getByte == 1
      }

      def encode(value: Boolean, builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = {
        builder.putByte(if (value) 1 else 0)
      }
    }


    object ArrayAnyCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder, commonCodec: CommonDataBinaryCodec): Array[Any] = {
        val len = bytes.getInt
        var cnt = 0
        val arr = Array.ofDim[Any](len)
        while (cnt < len) {
          arr(cnt) = commonCodec.decode(bytes)
          cnt += 1
        }
        arr
      }

      def encode(value: Array[Any], builder: ByteStringBuilder)(implicit byteOrder: ByteOrder, commonCodec: CommonDataBinaryCodec): Unit = {
        builder.putInt(value.length)
        var idx = 0
        while (idx < value.length) {
          val next = value(idx)
          commonCodec.encode(next, builder)
          idx += 1
        }
      }
    }

    object ArrayStringsCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): Array[String] = {
        val len = bytes.getInt
        var cnt = 0
        val arr = Array.ofDim[String](len)
        while (cnt < len) {
          arr(cnt) = StringCodecLogic.decode(bytes)
          cnt += 1
        }
        arr
      }

      def encode(value: Array[String], builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = {
        builder.putInt(value.length)
        var idx = 0
        while (idx < value.length) {
          val next = value(idx)
          StringCodecLogic.encode(next, builder)
          idx += 1
        }
      }
    }

    object SeqAnyCodecLogic {
      def decode[T](bytes: ByteIterator)(implicit commonCodec: CommonDataBinaryCodec, byteOrder: ByteOrder): List[T] = {
        val len = bytes.getInt
        var cnt = 0
        var list = List[T]()
        while (cnt < len) {
          list = commonCodec.decode(bytes).asInstanceOf[T] +: list
          cnt += 1
        }
        list.reverse
      }

      def encode(value: Seq[Any], builder: ByteStringBuilder)(implicit commonCodec: CommonDataBinaryCodec, byteOrder: ByteOrder): Unit = {
        builder.putInt(value.size)
        value foreach (commonCodec.encode(_, builder))
      }
    }

    object ListSpecsCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): ListSpecs = {
        val max = bytes.getShort
        val strategy = bytes.getByte match {
          case 0 => RejectAdd
          case 1 => FromHead
          case 2 => FromTail
        }
        ListSpecs(max.toInt, strategy)
      }

      def encode(value: ListSpecs, builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = {
        builder.putShort(value.max)
        builder.putByte(value.evictionStrategy match {
          case RejectAdd => 0
          case FromHead => 1
          case FromTail => 2
        })
      }
    }


    object ListStringCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): List[String] = {
        val len = bytes.getInt
        var cnt = 0
        var list = List[String]()
        while (cnt < len) {
          list = list :+ StringCodecLogic.decode(bytes)
          cnt += 1
        }
        list
      }

      def encode(value: List[String], builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = {
        builder.putInt(value.size)
        value foreach (StringCodecLogic.encode(_, builder))
      }
    }

    object SetAnyCodecLogic {
      def decode(bytes: ByteIterator)(implicit commonCodec: CommonDataBinaryCodec, byteOrder: ByteOrder): Set[Any] = {
        val len = bytes.getInt
        var cnt = 0
        var set = Set[Any]()
        while (cnt < len) {
          set += commonCodec.decode(bytes)
          cnt += 1
        }
        set
      }

      def encode(value: Set[Any], builder: ByteStringBuilder)(implicit commonCodec: CommonDataBinaryCodec, byteOrder: ByteOrder): Unit = {
        builder.putInt(value.size)
        value foreach (commonCodec.encode(_, builder))
      }
    }

    object SetStringsCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): Set[String] = {
        val len = bytes.getInt
        var cnt = 0
        var set = Set[String]()
        while (cnt < len) {
          set += StringCodecLogic.decode(bytes)
          cnt += 1
        }
        set
      }

      def encode(value: Set[String], builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = {
        builder.putInt(value.size)
        value foreach (StringCodecLogic.encode(_, builder))
      }
    }

    object OptionAnyCodecLogic {
      def decode(bytes: ByteIterator)(implicit commonCodec: CommonDataBinaryCodec, byteOrder: ByteOrder): Option[Any] = {
        val flag = bytes.getByte
        if (flag == 0) None else Some(commonCodec.decode(bytes))
      }

      def encode(value: Option[Any], builder: ByteStringBuilder)(implicit commonCodec: CommonDataBinaryCodec, byteOrder: ByteOrder): Unit = {
        value match {
          case None => builder.putByte(0)
          case Some(x) => commonCodec.encode(x, builder.putByte(1))
        }
      }
    }

    object OptionStringCodecLogic {
      def decode(bytes: ByteIterator)(implicit byteOrder: ByteOrder): Option[String] = {
        val flag = bytes.getByte
        if (flag == 0) None else Some(StringCodecLogic.decode(bytes))
      }

      def encode(value: Option[String], builder: ByteStringBuilder)(implicit byteOrder: ByteOrder): Unit = {
        value match {
          case None => builder.putByte(0)
          case Some(x) => StringCodecLogic.encode(x, builder)
        }
      }
    }


    class DefaultClientBinaryCodec(implicit commonCodec: CommonDataBinaryCodec, idCodec: IdCodec, byteOrder: ByteOrder) extends ClientBinaryCodec {
      override def decode(bytes: ByteIterator): BinaryDialectOutbound = {
        val id = idCodec.decode(bytes)
        id match {
          case TypeServiceNotAvailable => BinaryDialectServiceNotAvailable(ServiceKeyCodecLogic.decode(bytes))
          case TypeSubscriptionClosed => BinaryDialectSubscriptionClosed(bytes.getInt)
          case TypeInvalidRequest => BinaryDialectInvalidRequest(bytes.getInt)
          case TypeStreamStateUpdate => BinaryDialectStreamStateUpdate(bytes.getInt, commonCodec.decode(bytes).asInstanceOf[StreamState])
          case TypePing => BinaryDialectPing(bytes.getInt)
          case TypeStreamStateTransitionUpdate => BinaryDialectStreamStateTransitionUpdate(bytes.getInt, commonCodec.decode(bytes).asInstanceOf[StreamStateTransition])
          case TypeSignalAckOk => BinaryDialectSignalAckOk(bytes.getInt, OptionAnyCodecLogic.decode(bytes), OptionAnyCodecLogic.decode(bytes))
          case TypeSignalAckFailed => BinaryDialectSignalAckFailed(bytes.getInt, OptionAnyCodecLogic.decode(bytes), OptionAnyCodecLogic.decode(bytes))
        }
      }

      override def encode(value: BinaryDialectInbound, builder: ByteStringBuilder): Unit = {
        def putId(id: Short) = idCodec.encode(id.toInt, builder)
        value match {
          case x: BinaryDialectOpenSubscription => putId(TypeOpenSubscription)
            builder.putInt(x.subjAlias)
            StringCodecLogic.encode(x.priorityKey.getOrElse(""), builder)
            builder.putInt(x.aggregationIntervalMs)
          case x: BinaryDialectResetSubscription => putId(TypeResetSubscription)
            builder.putInt(x.subjAlias)
          case x: BinaryDialectCloseSubscription => putId(TypeCloseSubscription); builder.putInt(x.subjAlias)
          case x: BinaryDialectAlias => putId(TypeAlias)
            builder.putInt(x.id)
            SubjectCodecLogic.encode(x.subj, builder)
          case x: BinaryDialectPong => putId(TypePong); builder.putInt(x.id)
          case x: BinaryDialectSignal => putId(TypeSignal)
            builder.putInt(x.subjAlias)
            commonCodec.encode(x.payload, builder)
            val seconds = (x.expireAt - System.currentTimeMillis()) / 1000
            if (seconds < 1)
              builder.putInt(0)
            else
              builder.putInt(seconds.toInt)
            OptionAnyCodecLogic.encode(x.orderingGroup, builder)
            OptionAnyCodecLogic.encode(x.correlationId, builder)
        }

      }
    }

    class DefaultServerBinaryCodec(implicit commonCodec: CommonDataBinaryCodec, idCodec: IdCodec, byteOrder: ByteOrder) extends ServerBinaryCodec {
      override def decode(bytes: ByteIterator): BinaryDialectInbound = {
        val id = idCodec.decode(bytes)
        id match {
          case TypeOpenSubscription =>
            val alias = bytes.getInt
            val prio = StringCodecLogic.decode(bytes)
            val rate = bytes.getInt
            BinaryDialectOpenSubscription(alias, if (prio.isEmpty) None else Some(prio), rate)
          case TypeResetSubscription =>
            val alias = bytes.getInt
            BinaryDialectResetSubscription(alias)
          case TypeCloseSubscription => BinaryDialectCloseSubscription(bytes.getInt)
          case TypeAlias => BinaryDialectAlias(bytes.getInt, SubjectCodecLogic.decode(bytes))
          case TypePong => BinaryDialectPong(bytes.getInt)
          case TypeSignal => BinaryDialectSignal(bytes.getInt, commonCodec.decode(bytes), System.currentTimeMillis() + bytes.getInt * 1000, OptionAnyCodecLogic.decode(bytes), OptionAnyCodecLogic.decode(bytes))
        }
      }

      override def encode(value: BinaryDialectOutbound, builder: ByteStringBuilder): Unit = {
        def putId(id: Short) = idCodec.encode(id.toInt, builder)
        value match {
          case x: BinaryDialectServiceNotAvailable => putId(TypeServiceNotAvailable); ServiceKeyCodecLogic.encode(x.serviceKey, builder)
          case x: BinaryDialectInvalidRequest => putId(TypeInvalidRequest); builder.putInt(x.subjAlias)
          case x: BinaryDialectStreamStateUpdate => putId(TypeStreamStateUpdate)
            builder.putInt(x.subjAlias)
            commonCodec.encode(x.topicState, builder)
          case x: BinaryDialectPing => putId(TypePing); builder.putInt(x.id)
          case x: BinaryDialectStreamStateTransitionUpdate => putId(TypeStreamStateTransitionUpdate)
            builder.putInt(x.subjAlias)
            commonCodec.encode(x.topicStateTransition, builder)
          case x: BinaryDialectSignalAckOk => putId(TypeSignalAckOk)
            builder.putInt(x.subjAlias)
            OptionAnyCodecLogic.encode(x.correlationId, builder)
            OptionAnyCodecLogic.encode(x.payload, builder)
          case x: BinaryDialectSignalAckFailed => putId(TypeSignalAckFailed)
            builder.putInt(x.subjAlias)
            OptionAnyCodecLogic.encode(x.correlationId, builder)
            OptionAnyCodecLogic.encode(x.payload, builder)
          case x: BinaryDialectSubscriptionClosed => putId(TypeSubscriptionClosed); builder.putInt(x.subjAlias)
        }

      }
    }


    class ByteIdCodec(implicit byteOrder: ByteOrder) extends IdCodec {
      override def decode(bytesIterator: ByteIterator): Int = bytesIterator.getByte.toInt

      override def encode(msg: Int, builder: ByteStringBuilder): Unit = builder.putByte(msg.toByte)
    }

    class ShortIdCodec(implicit byteOrder: ByteOrder) extends IdCodec {
      override def decode(bytesIterator: ByteIterator): Int = bytesIterator.getShort.toInt

      override def encode(msg: Int, builder: ByteStringBuilder): Unit = builder.putShort(msg.toShort.toInt)
    }

    class IntIdCodec(implicit byteOrder: ByteOrder) extends IdCodec {
      override def decode(bytesIterator: ByteIterator): Int = bytesIterator.getInt

      override def encode(msg: Int, builder: ByteStringBuilder): Unit = builder.putInt(msg)
    }

    class DefaultCommonDataBinaryCodec(implicit byteOrder: ByteOrder, idCodec: IdCodec) extends CommonDataBinaryCodec {

      implicit val cc = this

      override def decode(bytes: ByteIterator): Any = {
        val id = idCodec.decode(bytes)
        id match {
          case TypeNone => None
          case TypeNil => Nil
          case TypeByte => bytes.getByte
          case TypeShort => bytes.getShort
          case TypeInt => bytes.getInt
          case TypeLong => bytes.getLong
          case TypeFloat => bytes.getFloat
          case TypeDouble => bytes.getDouble
          case TypeBoolean => BooleanCodecLogic.decode(bytes)
          case TypeString => StringCodecLogic.decode(bytes)
          case TypeStringStreamState => StringStreamState(StringCodecLogic.decode(bytes))
          case TypeDictionaryMapStreamState => DictionaryMapStreamState(bytes.getInt, bytes.getInt, ArrayAnyCodecLogic.decode(bytes), Dictionary(ArrayStringsCodecLogic.decode(bytes)))
          case TypeDictionaryMapNoChange => NoChange
          case TypeDictionaryMapStreamTransitionPartial => DictionaryMapStreamTransitionPartial(bytes.getInt, bytes.getInt, bytes.getInt, ArrayAnyCodecLogic.decode(bytes))

          case TypeSetStreamState => SetStreamState(bytes.getInt, bytes.getInt, SetAnyCodecLogic.decode(bytes), SetSpecs(BooleanCodecLogic.decode(bytes)))
          case TypeSetStreamTransitionPartial => SetStreamTransitionPartial(bytes.getInt, bytes.getInt, bytes.getInt, SeqAnyCodecLogic.decode[SetOp](bytes))
          case TypeSetAddOp => Add(decode(bytes))
          case TypeSetRemoveOp => Remove(decode(bytes))

          case TypeListStreamState => ListStreamState(bytes.getInt, bytes.getInt, SeqAnyCodecLogic.decode(bytes), ListSpecsCodecLogic.decode(bytes), List.empty)
          case TypeListStreamTransitionPartial => ListStreamStateTransitionPartial(bytes.getInt, bytes.getInt, bytes.getInt, SeqAnyCodecLogic.decode[ListStreamState.Op](bytes))
          case TypeListAddOp => ListStreamState.Add(bytes.getShort.toInt, decode(bytes))
          case TypeListRemoveOp => ListStreamState.Remove(bytes.getShort.toInt)
          case TypeListReplaceOp => ListStreamState.Replace(bytes.getShort.toInt, decode(bytes))
        }
      }

      override def encode(value: Any, builder: ByteStringBuilder): Unit = {
        def putId(id: Short) = idCodec.encode(id.toInt, builder)
        value match {
          case Nil => putId(TypeNil)
          case x: Byte => putId(TypeByte); builder.putByte(x)
          case x: Short => putId(TypeShort); builder.putShort(x.toInt)
          case x: Int => putId(TypeInt); builder.putInt(x)
          case x: Long => putId(TypeLong); builder.putLong(x)
          case x: Float => putId(TypeFloat); builder.putFloat(x)
          case x: Double => putId(TypeDouble); builder.putDouble(x)
          case x: Boolean => putId(TypeBoolean); BooleanCodecLogic.encode(x, builder)
          case x: String => putId(TypeString); StringCodecLogic.encode(x, builder)
          case x: ByteString => putId(TypeString); StringCodecLogic.encode(x.utf8String, builder)
          case x: StringStreamState => putId(TypeStringStreamState); StringCodecLogic.encode(x.value, builder)

          case x: DictionaryMapStreamState => putId(TypeDictionaryMapStreamState)
            builder.putInt(x.seed)
            builder.putInt(x.seq)
            ArrayAnyCodecLogic.encode(x.values, builder)
            ArrayStringsCodecLogic.encode(x.dict.fields, builder)
          case NoChange => putId(TypeDictionaryMapNoChange)
          case x: DictionaryMapStreamTransitionPartial => putId(TypeDictionaryMapStreamTransitionPartial)
            builder.putInt(x.seed)
            builder.putInt(x.seq)
            builder.putInt(x.seq2)
            ArrayAnyCodecLogic.encode(x.diffs, builder)

          case x: SetStreamState => putId(TypeSetStreamState)
            builder.putInt(x.seed)
            builder.putInt(x.seq)
            SetAnyCodecLogic.encode(x.set, builder)
            BooleanCodecLogic.encode(x.specs.allowPartialUpdates, builder)
          case x: SetStreamTransitionPartial => putId(TypeSetStreamTransitionPartial)
            builder.putInt(x.seed)
            builder.putInt(x.seq)
            builder.putInt(x.seq2)
            SeqAnyCodecLogic.encode(x.list, builder)
          case x: Add => putId(TypeSetAddOp); encode(x.el, builder)
          case x: Remove => putId(TypeSetRemoveOp); encode(x.el, builder)

          case x: ListStreamState => putId(TypeListStreamState)
            builder.putInt(x.seed)
            builder.putInt(x.seq)
            SeqAnyCodecLogic.encode(x.list, builder)
            ListSpecsCodecLogic.encode(x.specs, builder)
          case x: ListStreamStateTransitionPartial => putId(TypeListStreamTransitionPartial)
            builder.putInt(x.seed)
            builder.putInt(x.seq)
            builder.putInt(x.seq2)
            SeqAnyCodecLogic.encode(x.list, builder)
          case x: ListStreamState.Add => putId(TypeListAddOp); builder.putShort(x.pos); encode(x.v, builder)
          case x: ListStreamState.Remove => putId(TypeListRemoveOp); builder.putShort(x.pos)
          case x: ListStreamState.Replace => putId(TypeListReplaceOp); builder.putShort(x.pos); encode(x.v, builder)
          case _ => putId(TypeNone)
        }
      }

    }

  }

}
