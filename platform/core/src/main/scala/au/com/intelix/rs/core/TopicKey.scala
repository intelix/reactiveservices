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
package au.com.intelix.rs.core

case class TopicKey(id: String) extends Ser {
  override def toString: String = id
}

object TopicKey {

  import scala.language.implicitConversions

  implicit def toTopicKey(id: String): TopicKey = TopicKey(id)
}


object CompositeTopicKey {
  def apply(prefix: String, entityId: String) = new TopicKey(prefix + ":" + entityId)

  def unapply(t: TopicKey): Option[(String, String)] = t.id match {
    case s if s.contains(':') =>
      val idx = s.indexOf(':')
      Some((s.substring(0, idx), s.substring(idx + 1)))
    case _ => None
  }
}

object ComplexTopicKey {
  def apply(prefix: String, entityId: String*) = new TopicKey(prefix + ":" + entityId.mkString("~"))

  def unapply(t: TopicKey): Option[(String, Array[String])] = t.id match {
    case s if s.contains(':') =>
      val idx = s.indexOf(':')
      Some((s.substring(0, idx), s.substring(idx + 1).split('~')))
    case _ => None
  }
}


