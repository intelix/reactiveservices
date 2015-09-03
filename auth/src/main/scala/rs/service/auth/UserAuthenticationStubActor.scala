package rs.service.auth

import rs.service.auth.UserAuthenticationActor.User

object UserAuthenticationStubActor {
}

class UserAuthenticationStubActor(id: String) extends UserAuthenticationActor(id) {

  private val coffee = "37290d74ac4d186e3a8e5785d259d2ec04fac91ae28092e7620ec8bc99e830aa"
  private val tempCredentials: Seq[User] = Seq(
    User("user1", coffee, Set(CanAccessBlotter, CanAccessAUD)),
    User("user2", coffee, Set(CanAccessBlotter)),
    User("user3", coffee, Set(CanAccessBlotter, CanAccessAUD))
  )

  override def user(userId: String): Option[User] = tempCredentials.find(_.login == userId)

}
