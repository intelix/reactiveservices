package au.com.intelix.evt



trait Evt {

  def name: String
  def level: EvtLevel

}

trait EvtWithDerivedName extends Evt {
  override val name: String = toString
}

trait TraceE extends EvtWithDerivedName {
  override def level: EvtLevel = EvtLevelTrace
}
trait InfoE extends EvtWithDerivedName {
  override def level: EvtLevel = EvtLevelInfo
}
trait WarningE extends EvtWithDerivedName {
  override def level: EvtLevel = EvtLevelWarning
}
trait ErrorE extends EvtWithDerivedName {
  override def level: EvtLevel = EvtLevelError
}
