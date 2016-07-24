package au.com.intelix.rs.core.serializer

import akka.actor.{Extension, ActorRef, ExtendedActorSystem}
import akka.serialization.Serializer
import net.jpountz.lz4.LZ4Factory
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.rs.core.serializer.RSThreadUnsafeCodec.ExternalCodecs

class AkkaSerializer(val system: ExtendedActorSystem) extends Serializer with Extension {

  val config = system.settings.config

  lazy val CompressionEnabled = config asBoolean("rs.serialization.compression", true)

  override def identifier: Int = 124678899

  override def includeManifest: Boolean = false


  private class LZ4Codec {
    lazy val lz4factory = LZ4Factory.fastestInstance

    val localBuffers = new ThreadLocal[Array[Byte]] {
      override def initialValue(): Array[Byte] = Array.ofDim(1024)
    }

    def encode(input: Array[Byte]): Array[Byte] = {
      val inputSize = input.length
      val lz4 = lz4factory.fastCompressor
      val maxCompressedLength = lz4.maxCompressedLength(inputSize)
      val localBuffer = localBuffers.get() match {
        case i if i.length >= maxCompressedLength + 4 => i
        case _ if inputSize > 1024 * 1024 => new Array[Byte](maxCompressedLength + 4)
        case _ => val newBuff = new Array[Byte](maxCompressedLength + 4); localBuffers.set(newBuff); newBuff
      }
      val size = lz4.compress(input, 0, inputSize, localBuffer, 4, maxCompressedLength)

      localBuffer(0) = (inputSize >> 24).toByte
      localBuffer(1) = (inputSize >> 16).toByte
      localBuffer(2) = (inputSize >> 8).toByte
      localBuffer(3) = inputSize.toByte

      localBuffer.take(size + 4)
    }

    def decode(input: Array[Byte]): Array[Byte] = {
      val size: Int = input(0) << 24 | (input(1) & 0xFF) << 16 | (input(2) & 0xFF) << 8 | (input(3) & 0xFF)
      val lz4 = lz4factory.fastDecompressor()
      val localBuffer = localBuffers.get() match {
        case i if i.length >= size => i
        case _ if size > 1024 * 1024 => new Array[Byte](size)
        case _ => val newBuff = new Array[Byte](size); localBuffers.set(newBuff); newBuff
      }
      lz4.decompress(input, 4, localBuffer, 0, size)
      localBuffer.take(size)
    }
  }

  private lazy val lz4Codec = new LZ4Codec
  implicit val ec: ExternalCodecs = new ExternalCodecs {
    override def resolveActorRef(s: String): ActorRef = system.provider.resolveActorRef(s)
  }

  val localCodecs = new ThreadLocal[RSThreadUnsafeCodec] {
    override def initialValue(): RSThreadUnsafeCodec = new RSThreadUnsafeCodec
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef =
    localCodecs.get().decode(if (CompressionEnabled) lz4Codec.decode(bytes) else bytes)


  def toBinary(obj: AnyRef): Array[Byte] = {
    val output = localCodecs.get().encode(obj)
    if (CompressionEnabled) lz4Codec.encode(output) else output
  }



}
