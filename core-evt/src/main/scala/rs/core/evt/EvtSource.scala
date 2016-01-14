package rs.core.evt


trait EvtSource {

  val evtSourceId: String

}

case class StringEvtSource(evtSourceId: String) extends EvtSource