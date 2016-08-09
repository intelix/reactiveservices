package au.com.intelix.evt.slf4j

import akka.util.ByteString
import au.com.intelix.essentials.uuid.UUIDTools
import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json._
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.evt.{EvtPublisher, _}


class Slf4jPublisher(cfg: Config) extends EvtPublisher with EvtMutingSupport {

  private case class EventKey(source: EvtSource, event: Evt)

  override val eventsConfig: Config = cfg.getConfig("evt.log")

  val eventLoggerName: String = eventsConfig.asString("logger-name", "evt.logger")
  val exceptionLoggerName: Option[String] = eventsConfig.asOptString("exceptions-logger-name")
  val logFormat: String = eventsConfig.asString("format", "%35s - %-30s : %s")
  val fieldPrefix: String = eventsConfig.asString("field-prefix", "#")
  val fieldPostfix: String = eventsConfig.asString("field-postfix", "=")
  val fieldsSeparator: String = eventsConfig.asString("field-separator", "  ")

  val staticEventLoggerName = eventLoggerName.indexOf('$') == -1
  val staticExceptionLoggerName = exceptionLoggerName.isDefined && exceptionLoggerName.get.indexOf('$') == -1

  var loggersCache = CacheBuilder.newBuilder.maximumSize(10000).concurrencyLevel(1)
    .build(new CacheLoader[String, Logger] {
      override def load(k: String): Logger = LoggerFactory.getLogger(k)
    })
  val eventLevelOverrides = eventsConfig.asMap("levels").map {
    case (k,v) => k -> EvtLevelTrace
  }
  val disabledFields = eventsConfig.asStringList("disabled-fields").toSet

  override def evt(s: EvtSource, e: String, lvl: EvtLevel, fields: Seq[EvtFieldValue]): Unit = if (canPublish(s, e, lvl)) logEvent(s, e, lvl, fields)


  private def logEvent(source: EvtSource, event: String, lvl: EvtLevel, values: Seq[EvtFieldValue]) = {
    try {
      val logger = loggerFor(buildEventLoggerName(source.evtSourceId, event))
      eventLevelOverrides.getOrElse(event, lvl) match {
        case EvtLevelTrace => if (logger.isDebugEnabled) logger.debug(buildEventLogMessage(source, event, lvl, values))
        case EvtLevelInfo => if (logger.isInfoEnabled) logger.info(buildEventLogMessage(source, event, lvl, values))
        case EvtLevelWarning => if (logger.isWarnEnabled) logger.warn(buildEventLogMessage(source, event, lvl, values))
        case EvtLevelError => if (logger.isErrorEnabled) logger.error(buildEventLogMessage(source, event, lvl, values))
      }
    } catch {
      case x: Throwable => loggerFor("errors").warn(s"Unable to raise event $event from $source", x)
    }
  }


  private def replace(where: String, what: String, wit: String) =
    where.indexOf(what) match {
      case i if i < 0 => where
      case i => where.substring(0, i) + wit + where.substring(i + what.length)
    }

  private def buildEventLoggerName(source: String, evt: String) =
    if (!staticEventLoggerName) replace(replace(eventLoggerName, "%source", source), "%event", evt) else eventLoggerName

  private def buildExceptionLoggerName(source: String, evt: String) =
    if (!staticExceptionLoggerName) replace(replace(exceptionLoggerName.get, "%source", source), "%event", evt) else exceptionLoggerName.get

  private def buildEventLogMessage(source: EvtSource, event: String, lvl: EvtLevel, values: Seq[EvtFieldValue]): String = {
    val fields = values.foldLeft(new StringBuilder) {
      case (aggr, next) if isFieldEnabled(next._1) => aggr.append(fieldPrefix).append(next._1.name).append(fieldPostfix).append(transformValue(source, event, lvl, next._2)).append("  ")
      case (aggr, next) => aggr
    }
    logFormat.format(source.evtSourceId, event, fields.toString())
  }

  private def isFieldEnabled(f: Symbol): Boolean = disabledFields.isEmpty || disabledFields.contains(f.name)

  private def loggerFor(s: String) = loggersCache.get(s)

  private def logException(id: String, source: EvtSource, event: String, lvl: EvtLevel, x: Throwable) = {
    val logger = loggerFor(buildExceptionLoggerName(source.evtSourceId, event))
    if (logger.isErrorEnabled) logger.error("Reference: " + id, x)
  }

  private def transformValue(source: EvtSource, event: String, lvl: EvtLevel, v: Any): Any = v match {
    case JsString(s) => s
    case JsNumber(n) => n.toString()
    case JsBoolean(b) => b.toString
    case b@JsObject(_) => Json.stringify(b)
    case JsArray(arr) => arr.seq.mkString(",")
    case JsNull => ""
    case JsUndefined() => ""
    case s: ByteString => s"ByteString(${s.utf8String})"
    case s: String => s
    case n: Number => n
    case n: Long => n
    case n: Int => n
    case n: Double => n
    case n: Float => n
    case b: Boolean => b
    case x: Throwable if exceptionLoggerName.isEmpty => x.getStackTrace.foldLeft(x.getMessage)(_ + " <- " + _.toString)
    case x: Throwable =>
      val id = UUIDTools.generateUUID
      logException(id, source, event, lvl, x)
      s"${x.getMessage} [ref $id]"
    case b: JsValue => Json.stringify(b)
    case other => String.valueOf(other)
  }

}
