package rs.core.sysevents.log

import java.text.SimpleDateFormat
import java.util.Date

import rs.core.sysevents._

trait StandardLogMessageFormatterWithDate extends StandardLogMessageFormatter {

  def buildEventLogMessage(timestamp: Long, event: Sysevent, values: Seq[FieldAndValue]): String = {

    val date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    val d = date.format(new Date(timestamp))

    d + ": " + buildEventLogMessage(event, values)
  }
}

