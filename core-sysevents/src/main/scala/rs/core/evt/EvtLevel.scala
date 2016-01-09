package rs.core.evt


sealed trait EvtLevel

case object EvtLevelTrace extends EvtLevel
case object EvtLevelInfo extends EvtLevel
case object EvtLevelWarning extends EvtLevel
case object EvtLevelError extends EvtLevel
