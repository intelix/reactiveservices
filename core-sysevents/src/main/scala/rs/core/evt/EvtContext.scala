package rs.core.evt

import rs.core.config.ConfigOps.wrap
import rs.core.evt.disruptor.DisruptorPublisher

import scala.language.experimental.macros
import scala.language.implicitConversions
import scala.reflect.macros.blackbox


object EvtContext {
  val publisher = EvtSettings.config.asConfigurableInstance[EvtPublisher]("evt.publisher", classOf[DisruptorPublisher])
}

trait EvtContext {
  val evtPublisher = EvtContext.publisher

  def evtSource: EvtSource

  implicit def strToEvtSource(s: String): EvtSource = StringEvtSource(s)

  def raise(e: Evt, fields: (String, Any)*): Unit = macro EvtContextMacro.raise

  def run(e: Evt, fields: (String, Any)*)(f: Builder => Unit): Unit = macro EvtContextMacro.run

}

class Builder(val enabled: Boolean) {
  def doAdd(f: (String, Any)): Unit = println(s"!>>> Really added $f")
  def +(f: (String, Any)): Unit = macro BuilderMacro.+

//  override def result: Seq[(String, Any)] = Seq()
}

object DummyBuilder extends Builder(false)

private object BuilderMacro {
  type MyContext = blackbox.Context {type PrefixType = Builder}
  def +(c: MyContext)(f: c.Expr[(String, Any)]) = {
    import c.universe._
    val enabled = q"${c.prefix}.enabled"
    val doAdd = q"${c.prefix}.doAdd"
    q"if ($enabled) $doAdd($f)"
  }
}

private object EvtContextMacro {
  type MyContext = blackbox.Context {type PrefixType = EvtContext}

  def raise(c: MyContext)(e: c.Expr[Evt], fields: c.Expr[(String, Any)]*) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"
    if (fields.nonEmpty)
      q"if ($pub.canPublish($src, $e)) $pub.raise($src, $e, List(..$fields))"
    else
      q"if ($pub.canPublish($src, $e)) $pub.raise($src, $e, List.empty)"
  }

  def run(c: MyContext)(e: c.Expr[Evt], fields: c.Expr[(String, Any)]*)(f: c.Expr[Builder => Unit]) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"
    if (fields.nonEmpty)
      q"""
          if ($pub.canPublish($src, $e)) {
            val b = new Builder(true)
            $f(b)
            println("Hey...")
            $pub.raise ($src, $e, List(..$fields))
          } else {
            println("Yo...")
            $f(DummyBuilder)
          }
        """
    else
      q"""
          if ($pub.canPublish($src, $e)) {
            $f(1)
            $pub.raise ($src, $e, List.empty)
          }
        """

  }

}