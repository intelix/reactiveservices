package au.com.intelix.evt

import au.com.intelix.evt.NamingTools.{removePrefix, splitCamelCase}


private object NamingTools {
  val pattern = String.format("%s|%s|%s",
    "(?<=[A-Z])(?=[A-Z][a-z])",
    "(?<=[^A-Z])(?=[A-Z])",
    "(?<=[A-Za-z])(?=[^A-Za-z])"
  )

  def splitCamelCase(s: String): String = {
    s.replaceAll(pattern, " ")
  }

  def removePrefix(s: String): String = s match {
    case _ if s startsWith "Evt" => s.substring(3)
    case _ => s
  }

}

trait Evt {

  protected def customName: String

  final val name: String = if (customName == "") toString else customName
  final val humanFriendlyName: String = if (customName == "") splitCamelCase(removePrefix(toString)) else customName

  def level: EvtLevel

}


//trait EvtWithDerivedName extends Evt {
//  override val name: String = toString
//}

class TraceE(val customName: String = "") extends Evt {
  override def level: EvtLevel = EvtLevelTrace
}

class InfoE(val customName: String = "") extends Evt {
  override def level: EvtLevel = EvtLevelInfo
}

class WarningE(val customName: String = "") extends Evt {
  override def level: EvtLevel = EvtLevelWarning
}

class ErrorE(val customName: String = "") extends Evt {
  override def level: EvtLevel = EvtLevelError
}
