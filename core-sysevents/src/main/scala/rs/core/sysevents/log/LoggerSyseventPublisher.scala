package rs.core.sysevents.log

import rs.core.config.ConfigOps.wrap
import rs.core.config.WithExternalConfig
import rs.core.sysevents._

class LoggerSyseventPublisher extends WithExternalConfig with SyseventPublisher with EventLogger with StandardLogMessageFormatter {

  override def contextFor(event: Sysevent, values: => Seq[(Symbol, Any)]) = new ContextWithFields(event, values)

  override def publish(ctx: SyseventPublisherContext): Unit =
    ctx match {
      case MuteContext =>
      case t: ContextWithFields => logEvent(t.event, t.fields)
    }

}
