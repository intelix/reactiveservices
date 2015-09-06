package rs.service.auth

import rs.service.auth.UserAuthenticationActor.User

object UserAuthenticationStubActor {
}

class UserAuthenticationStubActor(id: String) extends UserAuthenticationActor(id) {

  private val password123 = "ef92b778bafe771e89245b89ecbc08a44a4e166c06659911881f383d4473e94f"
  private val tempCredentials: Seq[User] = Seq(
    User("user1", password123, Set(CanAccessBlotter, CanAccessAUD)),
    User("user2", password123, Set(CanAccessBlotter)),
    User("user3", password123, Set(CanAccessBlotter, CanAccessAUD))
  )

  override def user(userId: String): Option[User] = tempCredentials.find(_.login == userId)

}
