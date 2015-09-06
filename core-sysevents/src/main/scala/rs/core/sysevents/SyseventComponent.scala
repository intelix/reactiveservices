package rs.core.sysevents

import scala.language.implicitConversions


trait SyseventComponent {

  implicit val component = this

  implicit def stringToSyseventOps(s: String): SyseventOps = new SyseventOps(s, this)

  implicit def symbolToSyseventOps(s: Symbol): SyseventOps = new SyseventOps(s.name, this)

  def trace(id: String) = new TraceSysevent(id, componentId)
  def info(id: String) = new InfoSysevent(id, componentId)
  def warn(id: String) = new WarnSysevent(id, componentId)
  def error(id: String) = new ErrorSysevent(id, componentId)

  def componentId: String
}
