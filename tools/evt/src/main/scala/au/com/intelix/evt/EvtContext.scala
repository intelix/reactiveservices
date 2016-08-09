package au.com.intelix.evt

import au.com.intelix.evt.disruptor.DisruptorPublisher
import au.com.intelix.config.CommonAttributes
import au.com.intelix.config.ConfigOps.wrap
import com.typesafe.config.{Config, ConfigFactory}

import scala.language.experimental.macros
import scala.language.implicitConversions
import scala.reflect.macros.blackbox


object EvtContext {
  val publisher = EvtSettings.config.asConfigurableInstance[EvtPublisher]("evt.publisher", classOf[DisruptorPublisher])

  def apply(evtSourceId: String, cfg: Config, staticFields: (Symbol, Any)*): EvtContext = new EvtContext {
    override val evtSource: EvtSource = evtSourceId
    cfg match {
      case t: CommonAttributes => commonEvtFields(t.commonAttributes.toList: _*)
      case _ =>
    }
    commonEvtFields(staticFields: _*)
  }

  def apply(evtSourceId: String, staticFields: (Symbol, Any)*): EvtContext = EvtContext(evtSourceId, ConfigFactory.empty(), staticFields: _*)
  def apply(evtSourceId: Class[_], cfg: Config, staticFields: (Symbol, Any)*): EvtContext = EvtContext(evtSourceId.getSimpleName, cfg, staticFields: _*)
  def apply(evtSourceId: Class[_], staticFields: (Symbol, Any)*): EvtContext = EvtContext(evtSourceId.getSimpleName, ConfigFactory.empty(), staticFields: _*)
}

trait EvtContext {
  val evtPublisher = EvtContext.publisher

  def evtSourceSubId: Option[String] = None
  val evtSource: EvtSource = getClass.getSimpleName + evtSourceSubId.map("." + _).getOrElse("")

  var commonFields: List[EvtFieldValue] = List.empty

  implicit def strToEvtSource(s: String): EvtSource = StringEvtSource(s)

  def raise(e: Evt, fields: EvtFieldValue*): Unit = macro EvtContextMacro.raise
  def evt(e: Evt, fields: EvtFieldValue*): Unit = macro EvtContextMacro.raise

//  def evtTrace(e: String, fields: EvtFieldValue*): Unit = macro EvtContextMacro.evtTrace
//  def evt(e: String, fields: EvtFieldValue*): Unit = macro EvtContextMacro.evt
//  def evtWarn(e: String, fields: EvtFieldValue*): Unit = macro EvtContextMacro.evtWarn
//  def evtErr(e: String, fields: EvtFieldValue*): Unit = macro EvtContextMacro.evtErr

  //  def raiseWith[T](e: Evt, fields: EvtFieldValue*)(f: EvtFieldBuilder => T): T = macro EvtContextMacro.raiseWith[T]

  //  def raiseWithTimer[T](e: Evt, fields: EvtFieldValue*)(f: EvtFieldBuilder => T): T = macro EvtContextMacro.raiseWithTimer[T]

  def commonEvtFields(fields: EvtFieldValue*): Unit = commonFields ++= fields.toList.reverse
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

  def evt(c: MyContext)(e: c.Expr[String], fields: c.Expr[EvtFieldValue]*) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"
    val initialList = toList(c)(fields: _*)
    q"""
        if ($pub.canPublish($src, $e, au.com.intelix.evt.EvtLevelInfo)) $pub.evt($src, $e, au.com.intelix.evt.EvtLevelInfo, $initialList)"""
  }

  def evtTrace(c: MyContext)(e: c.Expr[String], fields: c.Expr[EvtFieldValue]*) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"
    val initialList = toList(c)(fields: _*)
    q"""
        if ($pub.canPublish($src, $e, au.com.intelix.evt.EvtLevelTrace)) $pub.evt($src, $e, au.com.intelix.evt.EvtLevelTrace, $initialList)"""
  }

  def evtWarn(c: MyContext)(e: c.Expr[String], fields: c.Expr[EvtFieldValue]*) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"
    val initialList = toList(c)(fields: _*)
    q"""
        if ($pub.canPublish($src, $e, au.com.intelix.evt.EvtLevelWarning)) $pub.evt($src, $e, au.com.intelix.evt.EvtLevelWarning, $initialList)"""
  }

  def evtErr(c: MyContext)(e: c.Expr[String], fields: c.Expr[EvtFieldValue]*) = {
    import c.universe._
    val pub = q"${c.prefix}.evtPublisher"
    val src = q"${c.prefix}.evtSource"
    val initialList = toList(c)(fields: _*)
    q"""
        if ($pub.canPublish($src, $e, au.com.intelix.evt.EvtLevelError)) $pub.evt($src, $e, au.com.intelix.evt.EvtLevelError, $initialList)"""
  }


}