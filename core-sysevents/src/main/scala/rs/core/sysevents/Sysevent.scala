package rs.core.sysevents

import core.sysevents._

import scala.language.implicitConversions

sealed trait Sysevent {


  def id: String

  def componentId: String

  type EffectBlock[T] = SyseventPublisherContext => T

  private val dummy: EffectBlock[Unit] = SyseventPublisherContext => ()

  private def run[T](ff: => Seq[FieldAndValue], f: EffectBlock[T])(implicit ctx: WithSyseventPublisher, system: SyseventSystem): T = {
    val eCtx = ctx.evtPublisher.contextFor(system, this, ff)
    val start = if (eCtx.isMute) 0 else System.nanoTime()

    try f(eCtx) catch {
      case e: Throwable =>
        eCtx + ('Exception -> e)
        throw e
    } finally {
      if (!eCtx.isMute) {
        if (ctx.commonFields.nonEmpty) eCtx ++ ctx.commonFields
        val diff = System.nanoTime() - start
        eCtx + ('ms -> ((diff / 1000).toDouble / 1000))
      }
      ctx.evtPublisher.publish(eCtx)
    }
  }

  def apply[T](f: EffectBlock[T])(implicit ctx: WithSyseventPublisher, system: SyseventSystem): T = run(Seq.empty, f)

  def apply[T](f1: => FieldAndValue)(implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = run(Seq(f1), dummy)

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = run(Seq(f1, f2), dummy)

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = run(Seq(f1, f2, f3), dummy)

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue,
               f4: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = run(Seq(f1, f2, f3, f4), dummy)

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue,
               f4: => FieldAndValue,
               f5: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = run(Seq(f1, f2, f3, f4, f5), dummy)

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue,
               f4: => FieldAndValue,
               f5: => FieldAndValue,
               f6: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = run(Seq(f1, f2, f3, f4, f5, f6), dummy)

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue,
               f4: => FieldAndValue,
               f5: => FieldAndValue,
               f6: => FieldAndValue,
               f7: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher, system: SyseventSystem): Unit = run(Seq(f1, f2, f3, f4, f5, f6, f7), dummy)

}


trait SyseventImplicits {
  implicit def stringToSyseventOps(s: String)(implicit component: SyseventComponent): SyseventOps = new SyseventOps(s, component)

  implicit def symbolToSyseventOps(s: Symbol)(implicit component: SyseventComponent): SyseventOps = new SyseventOps(s.name, component)
}

object SyseventOps extends SyseventImplicits


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



