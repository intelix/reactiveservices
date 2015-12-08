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
package rs.core

object SubjectTags {

  abstract class SubjectTag(tagId: String) {

    val tagPrefix = "+"
    val tagPostfix = ":"
    private lazy val completeTagId = tagPrefix + tagId + tagPostfix

    final def apply(value: String) = completeTagId + value

    final def unapply(value: String): Option[String] = {
      value.indexOf(completeTagId) match {
        case -1 => None
        case i => value.indexOf(tagPrefix, i + 1) match {
          case -1 => Some(value.substring(i + completeTagId.length))
          case j => Some(value.substring(i + completeTagId.length, j))
        }
      }
    }
  }


  object UserToken extends SubjectTag("ut")

  object UserId extends SubjectTag("uid")


}
