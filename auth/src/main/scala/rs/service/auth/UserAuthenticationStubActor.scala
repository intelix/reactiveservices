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
