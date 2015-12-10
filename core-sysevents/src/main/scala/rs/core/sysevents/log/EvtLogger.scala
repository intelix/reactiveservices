package rs.core.sysevents.log

import org.slf4j
import org.slf4j.{LoggerFactory, Logger}
import play.api.libs.json._
import rs.core.sysevents._

trait EvtLogger {
  self: LogMessageFormatter =>

  def logEvent(event: Sysevent, values: Seq[(Symbol, Any)]) = {
    val logger = loggerFor("sysevents." + event.componentId + "." + event.id)
    event match {
      case x: TraceSysevent if logger.isDebugEnabled => logger.debug(buildEventLogMessage(event, values))
      case x: InfoSysevent if logger.isInfoEnabled => logger.info(buildEventLogMessage(event, values))
      case x: WarnSysevent if logger.isWarnEnabled => logger.warn(buildEventLogMessage(event, values))
      case x: ErrorSysevent if logger.isErrorEnabled => logger.error(buildEventLogMessage(event, values))
      case _ => ()
    }
  }

  private var loggers: Map[String, Logger] = Map()

  private def loggerFor(s: String) = loggers.get(s) match {
    case Some(x) => x
    case None =>
      val logger: slf4j.Logger = LoggerFactory.getLogger(s)
      loggers = loggers + (s -> logger)
      logger
  }

  protected def transformValue(v: Any): Any = v match {
    case JsString(s) => s
    case JsNumber(n) => n.toString()
    case JsBoolean(b) => b.toString
    case b@JsObject(_) => Json.stringify(b)
    case JsArray(arr) => arr.seq.mkString(",")
    case JsNull => ""
    case JsUndefined() => ""
    case s: String => s
    case n: Number => n
    case n: Long => n
    case n: Int => n
    case n: Double => n
    case b: Boolean => b
    case b: JsValue => Json.stringify(b)
    case other => String.valueOf(other)
  }


}
