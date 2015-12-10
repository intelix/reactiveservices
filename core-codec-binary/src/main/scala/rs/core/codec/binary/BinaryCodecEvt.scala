package rs.core.codec.binary

import rs.core.sysevents.CommonEvt


trait BinaryCodecEvt extends CommonEvt {

  val MessageEncoded = "MessageEncoded".trace
  val MessageDecoded = "MessageDecoded".trace


  override def componentId: String = "Endpoint.BinaryCodec"
}

object BinaryCodecEvt extends BinaryCodecEvt