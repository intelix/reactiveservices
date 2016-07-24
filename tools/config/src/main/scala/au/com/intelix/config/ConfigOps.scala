/*
 * Copyright 2014-16 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package au.com.intelix.config

import akka.actor.Props
import com.typesafe.config.{Config, ConfigFactory, ConfigValue}
import net.ceedubs.ficus.Ficus._

import scala.collection.JavaConversions._
import scala.concurrent.duration.FiniteDuration
import scala.language.{implicitConversions, postfixOps}
import scala.util.Try
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

  def asString(key: String, defaultValue: String) = asOptString(key) | defaultValue

  def asInt(key: String, defaultValue: Int) = asOptInt(key) | defaultValue

  def asLong(key: String, defaultValue: Long) = asOptLong(key) | defaultValue

  def asBoolean(key: String, defaultValue: Boolean) = cfg.as[Option[Boolean]](fieldFor(key)) | defaultValue
  def asOptBoolean(key: String) = cfg.as[Option[Boolean]](fieldFor(key))

  def asOptFiniteDuration(key: String) = cfg.as[Option[FiniteDuration]](fieldFor(key))

  def asFiniteDuration(key: String, defaultValue: FiniteDuration) = cfg.as[Option[FiniteDuration]](fieldFor(key)) | defaultValue

  def asClass[T](key: String, defaultValue: Class[_]) = asOptClass[T](key) | defaultValue.asInstanceOf[Class[T]]

  def asConfigurableInstance[T](key: String, defaultClass: Class[_]): T = {
    val cl = asClass[T](key, defaultClass)
    Try(cl.getConstructor(classOf[Config])).map(_.newInstance(cfg)).getOrElse(cl.newInstance())
  }

  def asOptLong(key: String) = cfg.as[Option[Long]](fieldFor(key))

  def asOptInt(key: String) = cfg.as[Option[Int]](fieldFor(key))

  def asStringList(key: String) = cfg.as[Option[List[String]]](fieldFor(key)) | List.empty

  def asClassesList(key: String) = asStringList(key).map(Class.forName)

  def asOptClass[T](key: String): Option[Class[T]] = cfg.as[Option[String]](fieldFor(key)).map(Class.forName(_).asInstanceOf[Class[T]])

  def asOptProps[T](key: String, args: Any*) = asOptClass[T](key).map(Props(_, args: _*))

  def asRequiredProps[T](key: String, args: Any*)  = required(asOptClass[T](key).map(Props(_, args: _*)), key)

  def asMap(key: String): Map[String, ConfigValue] =
    if (cfg.hasPath(key)) cfg.getConfig(key).entrySet().toSet[java.util.Map.Entry[String, ConfigValue]].map { x => x.getKey -> x.getValue } toMap
    else Map.empty

  private def required[T](o: Option[T], key: String) = o.getOrElse(throw new RuntimeException(s"$key not provided"))

}
