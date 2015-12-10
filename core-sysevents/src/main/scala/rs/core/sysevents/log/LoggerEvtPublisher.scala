package rs.core.sysevents.log

import rs.core.sysevents._

class LoggerEvtPublisher extends EvtPublisher with EvtLogger with StandardLogMessageFormatter {

  override def contextFor(event: Sysevent, values: => Seq[(Symbol, Any)]) = new EvtContextWithFields(event, values)

  override def publish(ctx: EvtContext): Unit =
    ctx match {
      case EvtMuteContext =>
      case t: EvtContextWithFields => logEvent(t.event, t.fields)
    }

}
