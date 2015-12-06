package rs.service.auth.api

object AuthorisationMessages {

  case class PermissionsRequest(user: String)
  case class PermissionsRequestCancel(user: String)

  case class DomainPermissions(user: String, domains: Set[String])
  case class SubjectPermissions(user: String, patterns: Set[String])

}
