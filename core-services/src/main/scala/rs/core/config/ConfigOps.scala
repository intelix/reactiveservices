package rs.core.config

import com.typesafe.config.{ConfigFactory, Config}
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions
import scalaz.Scalaz._

object ConfigOps {
  implicit def wrap(cfg: Config): ConfigOps = new ConfigOps(cfg)
  implicit def wrap(cfg: WithConfig): ConfigOps = new ConfigOps(cfg.config)
}

class ConfigOps(cfg: Config) {

  private lazy val environment = System.getProperty("env").toLowerCase
  private lazy val hostname = System.getenv("HOSTNAME").toLowerCase

  private def matchLocalEnv(key: String): Boolean = key match {
    case s if s.contains('|') => s.split("[|]").exists(matchLocalEnv)
    case s => environment.startsWith(s) || hostname.startsWith(s)
  }


  private def fieldFor(key: String): String =
    if (cfg.hasPath(key)) cfg.getAnyRef(key) match {
      case m: Map[_, _] => m.collectFirst {
        case (s: String, v: Any) if matchLocalEnv(s.toLowerCase) => key + "." + s
      } getOrElse key
      case _ => key
    } else key


  def asOptConfigList(key: String) = cfg.as[Option[List[Config]]](fieldFor(key))

  def asOptConfig(key: String) = cfg.as[Option[Config]](fieldFor(key))

  def asOptString(key: String) = cfg.as[Option[String]](fieldFor(key))

  def asConfig(key: String) = cfg.as[Option[Config]](fieldFor(key)) | ConfigFactory.empty()

  def asString(key: String, defaultValue: String) = cfg.as[Option[String]](fieldFor(key)) | defaultValue

  def asInt(key: String, defaultValue: Int) = asOptInt(key) | defaultValue

  def asLong(key: String, defaultValue: Long) = asOptLong(key) | defaultValue

  def asBoolean(key: String, defaultValue: Boolean) = cfg.as[Option[Boolean]](fieldFor(key)) | defaultValue

  def asFiniteDuration(key: String, defaultValue: FiniteDuration) = cfg.as[Option[FiniteDuration]](fieldFor(key)) | defaultValue

  def asOptLong(key: String) = cfg.as[Option[Long]](fieldFor(key))

  def asOptInt(key: String) = cfg.as[Option[Int]](fieldFor(key))

  def asStringList(key: String) = cfg.as[Option[List[String]]](fieldFor(key)) | List.empty

  def asClassesList(key: String) = asStringList(key).map(Class.forName)

  def asOptClass(key: String) = cfg.as[Option[String]](fieldFor(key)).map(Class.forName)

}
