package rs.core.evt

import com.typesafe.config.Config
import rs.core.config.ConfigOps.wrap

import scala.collection.mutable.ListBuffer
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait EvtPublisher {
  def canPublish(s: EvtSource, e: Evt): Boolean = true

  def raise(s: EvtSource, e: Evt, fields: Seq[(String, Any)])
}

class EvtFieldBuilder {
  def +(f: (String, Any)): Unit = macro EvtFieldBuilderMacro.+
}
object MuteEvtFieldBuilder extends EvtFieldBuilder

class EvtFieldBuilderWithList(var list: List[(String, Any)]) extends EvtFieldBuilder {
  def add(f: (String, Any)) = list +:= f
  def result = list.reverse
}

private object EvtFieldBuilderMacro {
  type MyContext = blackbox.Context {type PrefixType = EvtFieldBuilder}

  def +(c: MyContext)(f: c.Expr[(String, Any)]) = {
    import c.universe._
    val enabled = q"${c.prefix}.isInstanceOf[EvtFieldBuilderWithList]"
    val doAdd = q"${c.prefix}.asInstanceOf[EvtFieldBuilderWithList].add"
    q"if ($enabled) $doAdd($f)"

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

