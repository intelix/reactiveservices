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

import rs.core.actors.CommonActorEvt

trait AuthServiceEvt extends CommonActorEvt {

  val AuthToken = "AuthToken".trace
  val UserInfo = "UserInfo".trace
  val UserDomainPermissions = "UserDomainPermissions".trace
  val UserSubjectsPermissions = "UserSubjectsPermissions".trace
  val UserTokenAdded = "UserTokenAdded".info
  val SessionCreated = "SessionCreated".info
  val UserSessionExpired = "UserSessionExpired".info
  val UserSessionInvalidated = "UserSessionInvalidated".info
  val AuthRequest = "AuthRequest".info
  val SuccessfulCredentialsAuth = "SuccessfulCredentialsAuth".info
  val SuccessfulTokenAuth = "SuccessfulTokenAuth".info
  val FailedCredentialsAuth = "FailedCredentialsAuth".info
  val FailedTokenAuth = "FailedTokenAuth".info

  override def componentId: String = "Auth.Service"

}


object AuthServiceEvt extends AuthServiceEvt
