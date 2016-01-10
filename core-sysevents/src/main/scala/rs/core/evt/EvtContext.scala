package rs.core.evt

import rs.core.config.ConfigOps.wrap
import rs.core.evt.disruptor.DisruptorPublisher

import scala.collection.mutable.ListBuffer
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

  def raiseWith[T](e: Evt, fields: (String, Any)*)(f: EvtFieldBuilder => T): T = macro EvtContextMacro.raiseWith[T]
//  def raiseWithTimer[T](e: Evt, fields: (String, Any)*)(f: EvtFieldBuilder => T): T = macro EvtContextMacro.raiseWithTimer[T]

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
      q"if ($pub.canPublish($src, $e)) $pub.raise($src, $e, List.empty[(String, Any)])"
  }

  def raiseWith[T](c: MyContext)(e: c.Expr[Evt], fields: c.Expr[(String, Any)]*)(f: c.Expr[EvtFieldBuilder => T]) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"

    val initialList = if (fields.nonEmpty) q"List[(String, Any)](..$fields)" else q"List.empty[(String, Any)]"

    q"""
        if ($pub.canPublish($src, $e)) {
          val b = new EvtFieldBuilderWithList($initialList)
          val result = $f(b)
          $pub.raise ($src, $e, b.result)
          result
        } else {
          $f(MuteEvtFieldBuilder)
        }
      """

  }
/*
  def raiseWithTimer[T](c: MyContext)(e: c.Expr[Evt], fields: c.Expr[(String, Any)]*)(f: c.Expr[EvtFieldBuilder => T]) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"
    q"""
        if ($pub.canPublish($src, $e)) {
          val list = scala.collection.mutable.ListBuffer[(String, Any)](..$fields)
          val b = new EvtFieldBuilderWithList(list)
          val start = java.lang.System.nanoTime()
          val result = $f(b)
          val diff = java.lang.System.nanoTime() - start
          list.append(("ms", (diff / 1000).toDouble / 1000))
          $pub.raise ($src, $e, list)
          result
        } else {
          $f(MuteEvtFieldBuilder)
        }
      """
  }
*/
}