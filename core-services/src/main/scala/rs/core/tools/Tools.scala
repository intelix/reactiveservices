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

package rs.core.tools

import play.api.libs.json._

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}
import scalaz.Scalaz._

object Tools {


  implicit def configHelper(config: JsValue): ConfigExtOps = ConfigExtOps(config)

  implicit def configHelper(config: Option[JsValue]): ConfigExtOps = ConfigExtOps(config | Json.obj())


  case class ConfigExtOps(config: JsValue) {

    def #>(key: String) = (config \ key).asOpt[JsValue]

    def #>(key: Symbol) = (config \ key.name).asOpt[JsValue]

    def ~>(key: String) = (config \ key).asOpt[String] match {
      case Some(s) if !s.trim.isEmpty => Some(s)
      case _ => None
    }

    def ~>(key: Symbol) = (config \ key.name).asOpt[String] match {
      case Some(s) if !s.trim.isEmpty => Some(s)
      case _ => None
    }

    def ~*>(key: String) = (config \ key).asOpt[String]

    def ~*>(key: Symbol) = (config \ key.name).asOpt[String]


    def +>(key: String) = (config \ key).asOpt[Int]

    def +>(key: Symbol) = (config \ key.name).asOpt[Int]

    def +&>(key: String) = (config \ key).asOpt[Double]

    def +&>(key: Symbol) = (config \ key.name).asOpt[Double]

    def ++>(key: String) = (config \ key).asOpt[Long]

    def ++>(key: Symbol) = (config \ key.name).asOpt[Long]

    def ?>(key: String) = (config \ key).asOpt[Boolean]

    def ?>(key: Symbol) = (config \ key.name).asOpt[Boolean]

    def ##>(key: String) = (config \ key).asOpt[JsArray].map(_.value)

    def ##>(key: Symbol) = (config \ key.name).asOpt[JsArray].map(_.value)

    def ##|>(key: String) = (config \ key).asOpt[JsArray].map(_.value) | Seq()

    def ##|>(key: Symbol) = (config \ key.name).asOpt[JsArray].map(_.value) | Seq()

  }


  private val arrayPath = "^([^(]+)[(]([\\d\\s]+)[)]".r
  private val arrayMatch = "^a(.)$".r
  private val singleTypeMatch = "^(.)$".r
  private val macroMatch = ".*[$][{]([^}]+)[}].*".r

  private val dateMatch = ".*[$][{]now:([^}]+)[}].*".r
  private val eventDateMatch = ".*[$][{]eventts:([^}]+)[}].*".r

  implicit def s2b(s: String): Boolean = s.toBoolean

  implicit def s2n(s: String): BigDecimal = BigDecimal(s)

  implicit def jv2b(v: JsValue): Boolean = v match {
    case JsString(s) => s.toBoolean
    case JsNumber(n) => if (n.toInt == 1) true else false
    case JsBoolean(b) => b
    case JsArray(arr) => arr.seq.nonEmpty
    case JsNull => false
    case JsObject(x) => x.seq.nonEmpty
    case JsUndefined() => false
  }

  implicit def jv2n(v: JsValue): BigDecimal = v match {
    case JsString("") => BigDecimal(0)
    case JsString(s) => Try {
      BigDecimal(s)
    } match {
      case Success(x) => x
      case Failure(e) =>
        BigDecimal(0)
    }
    case JsNumber(n) => BigDecimal("" + n)
    case JsBoolean(b) => BigDecimal(if (b) 1 else 0)
    case JsArray(arr) => BigDecimal(0)
    case JsNull => BigDecimal(0)
    case JsObject(x) => BigDecimal(0)
    case JsUndefined() => BigDecimal(0)
  }

  implicit def jv2s(v: JsValue): String = v match {
    case JsString(s) => s
    case JsNumber(n) => n.toString()
    case JsBoolean(b) => b.toString
    case JsObject(b) => b.toString()
    case JsArray(arr) => arr.seq.mkString(",")
    case JsNull => ""
    case JsUndefined() => ""
  }

  def toPath(infixPath: String) =
    infixPath
      .split('.')
      .flatMap(_.split("/"))
      .foldLeft[JsPath](__) {
      (path, nextKey) =>
        nextKey.trim match {
          case arrayPath(a, b) => (path \ a.trim)(b.trim.toIntExact)
          case value => path \ value
        }
    }


}
