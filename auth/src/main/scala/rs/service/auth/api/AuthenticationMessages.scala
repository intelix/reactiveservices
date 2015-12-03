package rs.service.auth.api

object AuthenticationMessages {

  case class Authenticate(user: String, password: String)

  case class AuthenticationResponse(allow: Boolean)

  case class Invalidate(user: String)

}
