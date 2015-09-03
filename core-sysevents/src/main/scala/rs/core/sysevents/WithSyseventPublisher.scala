package rs.core.sysevents

import core.sysevents.FieldAndValue

import scala.language.implicitConversions


trait WithSyseventPublisher  {

  implicit lazy val evtSystem = SyseventSystemRef.ref
  implicit lazy val evtPublisher = SyseventPublisherRef.ref

  implicit lazy val evtCtx = this

  def commonFields: Seq[FieldAndValue] = Seq()

}

