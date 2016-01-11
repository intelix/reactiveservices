package rs.core.evt

import com.typesafe.config.Config
import rs.core.config.ConfigOps.wrap

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait EvtPublisher {
  def canPublish(s: EvtSource, e: Evt): Boolean = true

  def raise(s: EvtSource, e: Evt, fields: Seq[EvtFieldValue])
}

class EvtFieldBuilder {
  def +(f: EvtFieldValue*): Unit = macro EvtFieldBuilderMacro.+
}

object MuteEvtFieldBuilder extends EvtFieldBuilder

class EvtFieldBuilderWithList(var list: List[EvtFieldValue]) extends EvtFieldBuilder {
  def add(f: EvtFieldValue) = list +:= f
  def add(f: List[EvtFieldValue]) = list = f.reverse ++ list

  def result = list.reverse
}

private object EvtFieldBuilderMacro {
  type MyContext = blackbox.Context {type PrefixType = EvtFieldBuilder}

  def +(c: MyContext)(f: c.Expr[EvtFieldValue]*) = {
    import c.universe._
    val enabled = q"${c.prefix}.isInstanceOf[EvtFieldBuilderWithList]"
    val doAdd = q"${c.prefix}.asInstanceOf[EvtFieldBuilderWithList].add"
    if (f.length == 1) {
      q"if ($enabled) $doAdd($f)"
    } else {
      q"if ($enabled) $doAdd(List(..$f))"
    }

  }
}


trait EvtMutingSupport extends EvtPublisher {
  val eventsConfig: Config

  lazy val disabledEvents = eventsConfig.asStringList("disabled-events").toSet
  lazy val eventLevel = EvtLevel(eventsConfig.asString("event-level", "trace"))

  private def isEnabled(s: EvtSource, e: Evt) =
    e.level >= eventLevel && (
      disabledEvents.isEmpty ||
        (!disabledEvents.contains(s.evtSourceId) && !disabledEvents.contains(e.name) && !disabledEvents.contains(s.evtSourceId + "." + e.name))
      )

  override def canPublish(s: EvtSource, e: Evt): Boolean = isEnabled(s, e) && super.canPublish(s, e)
}

