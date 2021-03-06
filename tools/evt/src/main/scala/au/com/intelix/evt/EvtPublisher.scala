package au.com.intelix.evt

import com.typesafe.config.Config
import au.com.intelix.config.ConfigOps.wrap

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait EvtPublisher {
  final def canPublish(s: EvtSource, e: Evt): Boolean = canPublish(s, e.name, e.level)
  def canPublish(s: EvtSource, e: String, lvl: EvtLevel): Boolean = true

  def evt(s: EvtSource, e: String, lvl: EvtLevel, fields: Seq[EvtFieldValue])
  final def raise(s: EvtSource, e: Evt, fields: Seq[EvtFieldValue]) = evt(s, e.name, e.level, fields)
}

trait EvtFieldBuilder {
  def add(f: EvtFieldValue): Unit
  def add(f: List[EvtFieldValue]): Unit
  def canBuild: Boolean
  def +(f: EvtFieldValue*): Unit = macro EvtFieldBuilderMacro.+
}

object MuteEvtFieldBuilder extends EvtFieldBuilder {
  override val canBuild: Boolean = false

  override def add(f: (Symbol, Any)): Unit = {}

  override def add(f: List[(Symbol, Any)]): Unit = {}
}

class EvtFieldBuilderWithList(var list: List[EvtFieldValue]) extends EvtFieldBuilder {
  def add(f: EvtFieldValue) = list +:= f
  def add(f: List[EvtFieldValue]) = list = f.reverse ++ list

  def result = list.reverse

  override val canBuild: Boolean = true
}

private object EvtFieldBuilderMacro {
  type MyContext = blackbox.Context {type PrefixType = EvtFieldBuilder}

  def +(c: MyContext)(f: c.Expr[EvtFieldValue]*) = {
    import c.universe._
    val enabled = q"${c.prefix}.canBuild"
    val doAdd = q"${c.prefix}.add"
    if (f.length == 1) {
      q"if ($enabled) $doAdd(${f(0)})"
    } else {
      q"if ($enabled) $doAdd(List(..$f))"
    }

  }
}


trait EvtMutingSupport extends EvtPublisher {
  val eventsConfig: Config

  lazy val disabledEvents = eventsConfig.asStringList("disabled-events").toSet
  lazy val eventLevel = EvtLevel(eventsConfig.asString("event-level", "trace"))

  private def isEnabled(s: EvtSource, e: String, lvl: EvtLevel) =
    lvl >= eventLevel && (
      disabledEvents.isEmpty ||
        (!disabledEvents.contains(s.evtSourceId) && !disabledEvents.contains(e) && !disabledEvents.contains(s.evtSourceId + "." + e))
      )

  override def canPublish(s: EvtSource, e: String, lvl: EvtLevel): Boolean = isEnabled(s, e, lvl) && super.canPublish(s, e, lvl)
}

