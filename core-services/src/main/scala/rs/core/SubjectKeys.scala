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

object SubjectKeys {

  trait KeyOps {

    val token: String
    val tokenPrefix = "+"
    val tokenPostfix = ":"
    private lazy val completeToken = tokenPrefix + token + tokenPostfix

    final def apply(value: String) = completeToken + value

    final def unapply(value: String): Option[String] = {
      value.indexOf(completeToken) match {
        case -1 => None
        case i => value.indexOf(tokenPrefix, i + 1) match {
          case -1 => Some(value.substring(i + completeToken.length))
          case j => Some(value.substring(i + completeToken.length, j))
        }
      }
    }
  }


  object UserToken extends KeyOps {
    override val token: String = "ut"
  }

  object UserId extends KeyOps {
    override val token: String = "uid"
  }


}
