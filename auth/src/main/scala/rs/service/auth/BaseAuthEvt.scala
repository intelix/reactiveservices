package rs.service.auth

import rs.core.actors.BaseActorSysevents

trait BaseAuthEvt extends BaseActorSysevents {

  val UserTokenSubscription = "UserTokenSubscription".trace
  val AuthToken = "AuthToken".trace
  val UserInfo = "UserInfo".trace
  val UserPermissions = "UserPermissions".trace
  val UserDomainPermissions = "UserDomainPermissions".trace
  val UserSubjectsPermissions = "UserSubjectsPermissions".trace
  val UserTokenInvalidated = "UserTokenInvalidated".info
  val UserTokenAdded = "UserTokenAdded".info
  val SessionCreated = "SessionCreated".info
  val UserSessionExpired = "UserSessionExpired".info
  val UserSessionInvalidated = "UserSessionInvalidated".info
  val AuthRequest = "AuthRequest".info
  val SuccessfulCredentialsAuth = "SuccessfulCredentialsAuth".info
  val SuccessfulTokenAuth = "SuccessfulTokenAuth".info
  val FailedCredentialsAuth = "FailedCredentialsAuth".info
  val FailedTokenAuth = "FailedTokenAuth".info

  override def componentId: String = "Service.Auth"

}


object BaseAuthEvt extends BaseAuthEvt
