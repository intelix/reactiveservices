package rs.core.sysevents.log

import rs.core.config.ConfigOps.wrap
import rs.core.config.WithExternalConfig
import rs.core.sysevents._

trait StandardLogMessageFormatter extends LogMessageFormatter with WithExternalConfig {

  val logFormat: String = globalConfig.asString("sysevents.log.format", "%35s - %-30s : %s")
  val fieldPrefix: String = globalConfig.asString("sysevents.log.field-prefix", "#")
  val fieldPostfix: String = globalConfig.asString("sysevents.log.field-postfix", "=")
  val fieldsSeparator: String = globalConfig.asString("sysevents.log.field-separator", "  ")

  def buildEventLogMessage(event: Sysevent, values: Seq[FieldAndValue]): String = {
    val fields = values.foldLeft(new StringBuilder) {
      (aggr, next) => aggr.append(fieldPrefix).append(next._1.name).append(fieldPostfix).append(next._2).append("  ")
    }
    logFormat.format(event.componentId, event.id, fields.toString())
  }
}
