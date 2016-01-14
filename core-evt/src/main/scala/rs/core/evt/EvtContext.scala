package rs.core.evt

import com.typesafe.config.{Config, ConfigFactory}
import rs.core.config.ConfigOps.wrap
import rs.core.config.{NodeConfig, WithNodeConfig}
import rs.core.evt.disruptor.DisruptorPublisher

import scala.language.experimental.macros
import scala.language.implicitConversions
import scala.reflect.macros.blackbox


object EvtContext {
  val publisher = EvtSettings.config.asConfigurableInstance[EvtPublisher]("evt.publisher", classOf[DisruptorPublisher])

  def apply(evtSourceId: String, cfg: Config, staticFields: (Symbol, Any)*): EvtContext = new EvtContext with WithNodeConfig {
    override val evtSource: EvtSource = evtSourceId
    override implicit lazy val nodeCfg: NodeConfig = NodeConfig(cfg)
    addEvtFields('nodeid -> nodeId)
    addEvtFields(staticFields: _*)
  }

  def apply(evtSourceId: String, staticFields: (Symbol, Any)*): EvtContext = EvtContext(evtSourceId, ConfigFactory.empty(), staticFields: _*)
}

trait EvtContext {
  val evtPublisher = EvtContext.publisher

  val evtSource: EvtSource

  var commonFields: List[EvtFieldValue] = List.empty

  implicit def strToEvtSource(s: String): EvtSource = StringEvtSource(s)

  def raise(e: Evt, fields: EvtFieldValue*): Unit = macro EvtContextMacro.raise

  //  def raiseWith[T](e: Evt, fields: EvtFieldValue*)(f: EvtFieldBuilder => T): T = macro EvtContextMacro.raiseWith[T]

  //  def raiseWithTimer[T](e: Evt, fields: EvtFieldValue*)(f: EvtFieldBuilder => T): T = macro EvtContextMacro.raiseWithTimer[T]

  def addEvtFields(fields: EvtFieldValue*): Unit = commonFields ++= fields.toList.reverse
}

private object EvtContextMacro {
  type MyContext = blackbox.Context {type PrefixType = EvtContext}

  private def toList(c: MyContext)(fields: c.Expr[EvtFieldValue]*) = {
    import c.universe._
    val cf = q"${c.prefix}.commonFields"
    fields.length match {
      case 0 => q"$cf"
      case 1 => q"${fields(0)} :: $cf"
      case 2 => q"${fields(1)} :: ${fields(0)} :: $cf"
      case 3 => q"${fields(2)} :: ${fields(1)} :: ${fields(0)} :: $cf"
      case 4 => q"${fields(3)} :: ${fields(2)} :: ${fields(1)} :: ${fields(0)} :: $cf"
      case 5 => q"${fields(4)} :: ${fields(3)} :: ${fields(2)} :: ${fields(1)} :: ${fields(0)} :: $cf"
      case 6 => q"${fields(5)} :: ${fields(4)} :: ${fields(3)} :: ${fields(2)} :: ${fields(1)} :: ${fields(0)} :: $cf"
      case _ => q"List[EvtFieldValue](..${fields.reverse}) ++ $cf"
    }
  }


  def raise(c: MyContext)(e: c.Expr[Evt], fields: c.Expr[EvtFieldValue]*) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"
    val initialList = toList(c)(fields: _*)
    q"""
        if ($pub.canPublish($src, $e)) $pub.raise($src, $e, $initialList)"""
  }

  def raiseWith[T](c: MyContext)(e: c.Expr[Evt], fields: c.Expr[EvtFieldValue]*)(f: c.Expr[EvtFieldBuilder => T]) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"

    val initialList = toList(c)(fields: _*)
    q"""
        if ($pub.canPublish($src, $e)) {
          val b = new rs.core.evt.EvtFieldBuilderWithList($initialList)
          val result = $f(b)
          $pub.raise ($src, $e, b.result)
          result
        } else {
          $f(rs.core.evt.MuteEvtFieldBuilder)
        }
      """

  }

  def raiseWithTimer[T](c: MyContext)(e: c.Expr[Evt], fields: c.Expr[EvtFieldValue]*)(f: c.Expr[EvtFieldBuilder => T]) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"
    val initialList = toList(c)(fields: _*)
    q"""
        if ($pub.canPublish($src, $e)) {
          val b = new rs.core.evt.EvtFieldBuilderWithList($initialList)
          val start = java.lang.System.nanoTime()
          val result = $f(b)
          val diff = java.lang.System.nanoTime() - start
          b + ('ms -> (diff / 1000).toDouble / 1000)
          $pub.raise ($src, $e, b.result)
          result
        } else {
          $f(rs.core.evt.MuteEvtFieldBuilder)
        }
      """
  }

}