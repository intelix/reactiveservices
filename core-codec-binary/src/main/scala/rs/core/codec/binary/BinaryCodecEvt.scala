package rs.core.codec.binary

import rs.core.sysevents.ref.ComponentWithBaseSysevents


trait BinaryCodecEvt extends ComponentWithBaseSysevents {

  val MessageEncoded = "MessageEncoded".trace
  val MessageDecoded = "MessageDecoded".trace


  override def componentId: String = "Endpoint.BinaryCodec"
}

object BinaryCodecEvt extends BinaryCodecEvt