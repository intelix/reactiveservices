package rs.core.sysevents

trait SyseventSystem {
  def id: String
}

case class SEvtSystem(id: String) extends SyseventSystem

object SyseventSystemRef {
  implicit var ref = SEvtSystem("default")
}