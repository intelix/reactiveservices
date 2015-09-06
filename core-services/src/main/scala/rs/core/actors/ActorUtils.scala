/*
 * Copyright 2014-15 Intelix Pty Ltd
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

package rs.core.actors

import akka.actor.{Actor, ActorRef}
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import rs.core.tools.UUIDTools

import scala.concurrent.duration.FiniteDuration
import scalaz.Scalaz._

trait ActorUtils extends Actor {

  val uuid = shortUUID

  def shortUUID: String = UUIDTools.generateShortUUID

  val config = context.system.settings.config

  private lazy val environment = System.getProperty("env").toLowerCase
  private lazy val hostname = System.getenv("HOSTNAME").toLowerCase

  private def matchLocalEnv(key: String): Boolean = key match {
    case s if s.contains('|') => s.split("[|]").exists(matchLocalEnv)
    case s => environment.startsWith(s) || hostname.startsWith(s)
  }


  private def fieldFor(key: String)(implicit cfg: Config): String =
    if (cfg.hasPath(key)) cfg.getAnyRef(key) match {
      case m: Map[_, _] => m.collectFirst {
        case (s: String, v: Any) if matchLocalEnv(s.toLowerCase) => key + "." + s
      } getOrElse key
      case _ => key
    } else key


  def configValueAsOptConfigList(key: String)(implicit cfg: Config) = cfg.as[Option[List[Config]]](fieldFor(key))

  def configValueAsOptConfig(key: String)(implicit cfg: Config) = cfg.as[Option[Config]](fieldFor(key))

  def configValueAsOptString(key: String)(implicit cfg: Config) = cfg.as[Option[String]](fieldFor(key))

  def configAsString(key: String, defaultValue: String)(implicit cfg: Config) = cfg.as[Option[String]](fieldFor(key)) | defaultValue

  def configValueAsOptLong(key: String)(implicit cfg: Config) = cfg.as[Option[Long]](fieldFor(key))

  def configValueAsOptInt(key: String)(implicit cfg: Config) = cfg.as[Option[Int]](fieldFor(key))

  def configValueAsStringList(key: String)(implicit cfg: Config) = cfg.as[Option[List[String]]](fieldFor(key)) | List.empty

  def configValueAsOptClass(key: String)(implicit cfg: Config) = cfg.as[Option[String]](fieldFor(key)).map(Class.forName)

  def scheduleOnce(in: FiniteDuration, msg: Any, to: ActorRef = self, from: ActorRef = self) = context.system.scheduler.scheduleOnce(in, to, msg)(context.system.dispatcher, from)

}
