package rs.core.sysevents

import java.text.SimpleDateFormat
import java.util.Date

import core.sysevents._
import org.slf4j._
import play.api.libs.json._

trait SyseventPublisherContext {
  def isMute: Boolean = false
  def +(field: => FieldAndValue): Unit
  def +(f1: => FieldAndValue, f2: => FieldAndValue): Unit = {
    this + f1
    this + f2
  }
  def +(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue): Unit = {
    this + f1
    this + f2
    this + f3
  }
  def +(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue): Unit = {
    this + f1
    this + f2
    this + f3
    this + f4
  }
  def +(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue, f5: => FieldAndValue): Unit = {
    this + f1
    this + f2
    this + f3
    this + f4
    this + f5
  }
  def ++(fields: => Seq[FieldAndValue]): Unit

}
class ContextWithFields(val system: SyseventSystem, val event: Sysevent, f: Seq[FieldAndValue]) extends SyseventPublisherContext {
  var fields = f
  override def +(field: => (Symbol, Any)) = fields = fields :+ field

  override def ++(ff: => Seq[(Symbol, Any)]): Unit = fields = fields ++ ff
}
case object MuteContext extends SyseventPublisherContext{

  override def isMute: Boolean = true

  override def +(field: => (Symbol, Any)): Unit = {}

  override def ++(fields: => Seq[(Symbol, Any)]): Unit = {}
}

trait SyseventPublisher {
  def contextFor(system: SyseventSystem, event: Sysevent, values: => Seq[FieldAndValue]): SyseventPublisherContext
  def publish(ctx: SyseventPublisherContext)
}

object LoggerSyseventPublisherHelper {
  val logFormat = "%25s - %-25s : %s"

  def log(event: Sysevent, system: SyseventSystem, values: Seq[FieldAndValue], f: String => Unit) = {

    def formatNextField(f: FieldAndValue) = f._1.name + "=" + f._2

    val fields = values.foldLeft("") {
      (aggr, next) => aggr + formatNextField(next) + "  "
    }

    val string = logFormat.format(event.componentId, event.id, fields)

    f(string)
  }
}

object LoggerSyseventPublisherWithDateHelper {
  val logFormat = "%23s : %25s - %-25s : %s"
  val date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

  def log(timestamp: Long, event: Sysevent, system: SyseventSystem, values: Seq[FieldAndValue], f: String => Unit) = {

    def formatNextField(f: FieldAndValue) = f._1.name + "=" + f._2

    val fields = values.foldLeft("") {
      (aggr, next) => aggr + formatNextField(next) + "  "
    }
    
    val d = date.format(new Date(timestamp))
    
    val string = logFormat.format(d, event.componentId, event.id, fields)

    f(string)
  }
}

trait LoggerSyseventPublisher extends SyseventPublisher {

  var loggers: Map[String, Logger] = Map()

  def loggerFor(s: String) = loggers.get(s) match {
    case Some(x) => x
    case None =>
      val logger: Logger = LoggerFactory.getLogger(s)
      loggers = loggers + (s -> logger)
      logger
  }

  def transformValue(v: Any): Any = v match {
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



  override def contextFor(system: SyseventSystem, event: Sysevent, values: => Seq[(Symbol, Any)]): SyseventPublisherContext = {
    new ContextWithFields(system, event, values)
  }

  override def publish(ctx: SyseventPublisherContext): Unit =
    ctx match {
      case MuteContext =>
      case t: ContextWithFields =>
        val system = t.system
        val event = t.event
        val values = t.fields
        val logger = loggerFor("sysevents." + system.id + "." + event.componentId + "." + event.id)
        event match {
          case x: TraceSysevent if logger.isDebugEnabled =>
            LoggerSyseventPublisherHelper.log(event, system, values, s => logger.debug(s))
          case x: InfoSysevent if logger.isInfoEnabled =>
            LoggerSyseventPublisherHelper.log(event, system, values, s => logger.info(s))
          case x: WarnSysevent if logger.isWarnEnabled =>
            LoggerSyseventPublisherHelper.log(event, system, values, s => logger.warn(s))
          case x: ErrorSysevent if logger.isErrorEnabled =>
            LoggerSyseventPublisherHelper.log(event, system, values, s => logger.error(s))
          case _ => ()
        }
    }

}

object SyseventPublisherRef {
  implicit var ref: SyseventPublisher = LoggerSyseventPublisher
}

object LoggerSyseventPublisher extends SyseventPublisher with LoggerSyseventPublisher {


}
