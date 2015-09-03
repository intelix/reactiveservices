package rs.core.sysevents

import core.sysevents.FieldAndValue

import scala.language.implicitConversions

sealed trait Sysevent {

  def id: String

  def componentId: String

  def >>>[T](f1: => Seq[FieldAndValue], f: => T)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): T = {
    var fields = ctx.commonFields match {
      case x if x.isEmpty => f1
      case x => f1 ++ x
    }
    val start = System.nanoTime()
    try f catch {
      case e: Throwable =>
        fields = fields :+ 'Exception -> e
        throw e
    } finally {
      val diff = System.nanoTime() - start
      fields = fields :+ 'ms -> ((diff / 1000).toDouble / 1000)
      ctx.evtPublisher.publish(system, this, fields)
    }
  }

  def >>>[T](f1: => Seq[FieldAndValue])(implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = {
    val fields = ctx.commonFields match {
      case x if x.isEmpty => f1
      case x => f1 ++ x
    }
    ctx.evtPublisher.publish(system, this, fields)
  }

  def >>()(implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = >>>(Seq())

  def >>(f1: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = >>>(Seq(f1))

  def >>(f1: => FieldAndValue, f2: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = >>>(Seq(f1, f2))

  def >>(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = >>>(Seq(f1, f2, f3))

  def >>(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = >>>(Seq(f1, f2, f3, f4))

  def >>(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue, f5: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = >>>(Seq(f1, f2, f3, f4, f5))

  def >>(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue, f5: => FieldAndValue, f6: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = >>>(Seq(f1, f2, f3, f4, f5, f6))


  def >>[T](f: => T)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): T = >>>(Seq.empty, f)

  def apply(f1: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): EventWithFields = new EventWithFields(this, Seq(f1))

  def apply(f1: => FieldAndValue, f2: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): EventWithFields = new EventWithFields(this, Seq(f1, f2))

  def apply(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): EventWithFields = new EventWithFields(this, Seq(f1, f2, f3))

  def apply(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): EventWithFields = new EventWithFields(this, Seq(f1, f2, f3, f4))

  def apply(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue, f5: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): EventWithFields = new EventWithFields(this, Seq(f1, f2, f3, f4, f5))

  def apply(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue, f5: => FieldAndValue, f6: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): EventWithFields = new EventWithFields(this, Seq(f1, f2, f3, f4, f5, f6))


}

class EventWithFields(se: Sysevent, fields: => Seq[FieldAndValue]) {
  def >>[T](implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = se >>>(fields, {})

  def >>[T](f: => T = {})(implicit ctx: WithSyseventPublisher, system: SyseventSystem): T = se >>>(fields, f)
}

object SyseventOps {
  implicit def stringToSyseventOps(s: String)(implicit component: SyseventComponent): SyseventOps = new SyseventOps(s, component)

  implicit def symbolToSyseventOps(s: Symbol)(implicit component: SyseventComponent): SyseventOps = new SyseventOps(s.name, component)
}

class SyseventOps(id: String, component: SyseventComponent) {
  def trace: Sysevent = TraceSysevent(id, component.componentId)

  def info: Sysevent = InfoSysevent(id, component.componentId)

  def warn: Sysevent = WarnSysevent(id, component.componentId)

  def error: Sysevent = ErrorSysevent(id, component.componentId)

}

case class TraceSysevent(id: String, componentId: String) extends Sysevent

case class InfoSysevent(id: String, componentId: String) extends Sysevent

case class WarnSysevent(id: String, componentId: String) extends Sysevent

case class ErrorSysevent(id: String, componentId: String) extends Sysevent



