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
package rs.core

case class Subject(service: ServiceKey, topic: TopicKey, tags: String = "") {
  @transient private lazy val asString = service.toString + "|" + topic.toString + (if (tags == "") "" else "|" + tags)

  override def toString: String = asString

  def +(otherTags: String): Subject = withTags(otherTags)
  def +(otherTags: Option[String]): Subject = withTags(otherTags)

  def withTags(otherTags: String): Subject = this.copy(tags = tags + otherTags)
  def withTags(otherTags: Option[String]): Subject = otherTags match {
    case Some(k) => this.copy(tags = tags + k)
    case None => this
  }

  def removeTags() = this.copy(tags = "")

}



