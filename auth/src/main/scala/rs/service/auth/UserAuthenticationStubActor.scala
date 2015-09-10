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
package rs.service.auth

import play.api.libs.json.Json
import rs.service.auth.UserAuthenticationActor.User

object UserAuthenticationStubActor {
}

class UserAuthenticationStubActor(id: String) extends UserAuthenticationActor(id) {

  val CanDoAnything = Json.stringify(Json.obj(
    "p" -> Json.arr(
      Json.obj(
        "d" -> Json.obj(
          "id" -> "*"
        ),
        "p" -> Json.arr(Json.obj(
          "t" -> ".*"
        ))
      )
    )
  ))

  val CanAccessBlotter = Json.stringify(Json.obj(
    "p" -> Json.arr(
      Json.obj(
        "d" -> Json.obj(
          "id" -> "blotter"
        ),
        "p" -> Json.arr(Json.obj(
          "t" -> "xx"
        ))
      )
    )
  ))
  val CanNotAccessBlotter = Json.stringify(Json.obj(
    "p" -> Json.arr(
      Json.obj(
        "d" -> Json.obj(
          "id" -> ""
        ),
        "p" -> Json.arr(Json.obj(
          "t" -> "xx"
        ))
      )
    )
  ))

  val CanAccessAUD = Json.stringify(Json.obj(
    "p" -> Json.arr(
      Json.obj(
        "d" -> Json.obj(
          "id" -> "xx"
        ),
        "p" -> Json.arr(Json.obj(
          "t" -> ".*"
        ))
      )
    )
  ))
  val CanNotAccessAUD = Json.stringify(Json.obj(
    "p" -> Json.arr(
      Json.obj(
        "d" -> Json.obj(
          "id" -> "xx"
        ),
        "p" -> Json.obj(
          "t" -> ".*AUD.*"
        )
      )
    )
  ))

  private val password123 = "ef92b778bafe771e89245b89ecbc08a44a4e166c06659911881f383d4473e94f"
  private val tempCredentials: Seq[User] = Seq(
    User("user1", password123, Set(CanAccessBlotter, CanAccessAUD)),
    User("user2", password123, Set(CanAccessBlotter)),
    User("user3", password123, Set(CanAccessBlotter, CanAccessAUD))
  )

  override def user(userId: String): Option[User] = tempCredentials.find(_.login == userId)

}
