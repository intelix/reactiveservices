package rs.core.evt


sealed trait EvtLevel {
  protected val l: Int
  def <=(other: EvtLevel) = l <= other.l
  def >=(other: EvtLevel) = l >= other.l
}

case object EvtLevelTrace extends EvtLevel {
  override protected val l: Int = 1
}
case object EvtLevelInfo extends EvtLevel {
  override protected val l: Int = 2
}
case object EvtLevelWarning extends EvtLevel {
  override protected val l: Int = 3
}
case object EvtLevelError extends EvtLevel {
  override protected val l: Int = 4
}

object EvtLevel {
  def apply(s: String): EvtLevel = s.toLowerCase match {
    case "info" => EvtLevelInfo
    case "debug" | "trace" => EvtLevelTrace
    case "warn" | "warning" => EvtLevelWarning
    case "error" => EvtLevelError
    case _ => EvtLevelTrace
  }
}