package rs.core.sysevents.log

import rs.core.sysevents._

trait LogMessageFormatter {
  def buildEventLogMessage(event: Sysevent, values: Seq[FieldAndValue]): String
}
