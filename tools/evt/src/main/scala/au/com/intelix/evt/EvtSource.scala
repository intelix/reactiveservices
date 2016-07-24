package au.com.intelix.evt

trait EvtSource {

  val evtSourceId: String

}

case class StringEvtSource(evtSourceId: String) extends EvtSource