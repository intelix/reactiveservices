package rs.core.sysevents.log

import rs.core.sysevents._

class LoggerSyseventPublisher extends SyseventPublisher with EventLogger with StandardLogMessageFormatter {

  override def contextFor(event: Sysevent, values: => Seq[(Symbol, Any)]) = new ContextWithFields(event, values)

  override def publish(ctx: SyseventPublisherContext): Unit =
    ctx match {
      case MuteContext =>
      case t: ContextWithFields => logEvent(t.event, t.fields)
    }

}
