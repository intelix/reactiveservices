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
import rs.core.SubjectKeys.UserToken
import rs.core.actors.{ActorWithTicks, BaseActorSysevents}
import rs.core.services.{ServiceCell, StreamId}
import rs.core.stream.SetStreamState.SetSpecs
import rs.core.stream.{SetStreamPublisher, StringStreamPublisher}
import rs.core.tools.Tools.configHelper
import rs.core.{Subject, TopicKey}
import rs.service.auth.UserAuthenticationActor.{Session, User}

import scala.concurrent.duration._
import scala.language.postfixOps

trait UserAuthenticationSysevents extends BaseActorSysevents {

  val UserTokenSubscription = "UserTokenSubscription".trace
  val AuthToken = "AuthToken".trace
  val UserInfo = "UserInfo".trace
  val UserPermissions = "UserPermissions".trace
  val UserTokenInvalidated = "UserTokenInvalidated".info
  val UserTokenAdded = "UserTokenAdded".info
  val SessionCreated = "SessionCreated".info
  val UserSessionExpired = "UserSessionExpired".info
  val AuthRequest = "AuthRequest".info
  val SuccessfulCredentialsAuth = "SuccessfulCredentialsAuth".info
  val SuccessfulTokenAuth = "SuccessfulTokenAuth".info
  val FailedCredentialsAuth = "FailedCredentialsAuth".info
  val FailedTokenAuth = "FailedTokenAuth".info

  override def componentId: String = "Service.Auth"

}

object UserAuthenticationSysevents extends UserAuthenticationSysevents

object UserAuthenticationActor {

  case class User(login: String, hash: String, permissions: Set[String])

  case class Session(userTokens: Set[String], userId: String, securityToken: String, idleSince: Option[Long])

}

abstract class UserAuthenticationActor(id: String)
  extends ServiceCell(id)
  with StringStreamPublisher
  with SetStreamPublisher
  with UserAuthenticationSysevents
  with ActorWithTicks {

  implicit val specs = SetSpecs(allowPartialUpdates = true)


  val TokenPrefix = "t"
  val PermissionsPrefix = "p"
  val InfoPrefix = "i"

  private val SessionTimeout = 5 minutes
  private var sessions: Set[Session] = Set.empty

  onSignal {
    case (Subject(_, TopicKey("authenticate"), UserToken(ut)), v: String) =>
      AuthRequest { ctx =>
        ctx + ('token -> ut)
        authenticateWithCredentials(v, ut) orElse authenticateWithToken(v, ut) orElse {
          FailedCredentialsAuth('login -> (Json.parse(v) ~> 'l))
          Some(SignalOk(Some(false)))
        }
      }
    case (a, b) =>
      Invalid('subj -> a, 'payload -> b)
      None
  }

  onSubjectSubscription {
    case s@Subject(_, TopicKey("token"), UserToken(ut)) => Some(tokenStream(ut))
    case Subject(_, TopicKey("permissions"), UserToken(ut)) => Some(permissionsStream(ut))
    case Subject(_, TopicKey("info"), UserToken(ut)) => Some(infoStream(ut))
  }

  onStreamActive {
    case StreamId(TokenPrefix, Some(ut: String)) => publishToken(ut)
    case StreamId(InfoPrefix, Some(ut: String)) => publishInfo(ut)
    case StreamId(PermissionsPrefix, Some(ut: String)) => publishPermissions(ut)
  }

  onTick {
    invalidateSessions()
  }


  def tokenStream(ut: String) = StreamId(TokenPrefix, Some(ut))

  def permissionsStream(ut: String) = StreamId(PermissionsPrefix, Some(ut))

  def infoStream(ut: String) = StreamId(InfoPrefix, Some(ut))

  def user(userId: String): Option[User]


  def sessionByUserToken(userToken: String) = sessions.find(_.userTokens.contains(userToken))

  def sessionByUserId(id: String) = sessions.find(_.userId == id)

  def publishToken(userToken: String): Unit = {
    val securityToken = sessionByUserToken(userToken) match {
      case Some(s) => s.securityToken
      case _ => ""
    }
    tokenStream(userToken) !~ securityToken
    AuthToken('token -> userToken, 'authkey -> securityToken)
  }

  def publishInfo(userToken: String): Unit = {
    val info = sessionByUserToken(userToken) match {
      case Some(s) => s.userId
      case _ => ""
    }
    infoStream(userToken) !~ info
    UserInfo('token -> userToken, 'info -> info)
  }

  def publishPermissions(userToken: String): Unit =
    UserPermissions { ctx =>
      val permissions = sessionByUserToken(userToken) match {
        case Some(s) => user(s.userId) match {
          case Some(u) => u.permissions
          case None => Set.empty[String]
        }
        case _ => Set.empty[String]
      }
      ctx +('token -> userToken, 'perm -> permissions.mkString(";"))
      permissionsStream(userToken) !% permissions
    }


  def removeUserToken(userToken: String): Unit = {
    sessions map {
      case s if s.userTokens.contains(userToken) => s.copy(userTokens = s.userTokens - userToken)
      case s => s
    }
    UserTokenInvalidated('token -> userToken)
  }

  def invalidateSessions() = sessions = sessions filter {
    case Session(ut, uid, at, Some(t)) if now - t > SessionTimeout.toMillis =>
      UserSessionExpired('userid -> uid, 'authkey -> at)
      false
    case _ => true
  } map {
    case s if s.userTokens.isEmpty => s.copy(idleSince = Some(now))
    case s => s
  }

  def hashFor(s: String): String = {
    val m = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))
    m.map("%02x".format(_)).mkString
  }


  def addUserToken(s: Session, ut: String) = sessions = sessions map {
    case x if x.userId == s.userId =>
      UserTokenAdded('token -> ut, 'userid -> x.userId, 'authkey -> x.securityToken)
      x.copy(userTokens = x.userTokens + ut, idleSince = None)
    case x => x
  }

  def publishAllForUserToken(ut: String) = {
    publishInfo(ut)
    publishToken(ut)
    publishPermissions(ut)
  }

  def createSession(ut: String, u: User): Session = {
    val sess = sessionByUserToken(ut) orElse sessionByUserId(u.login) match {
      case None =>
        val newSess = Session(Set(ut), u.login, shortUUID, None)
        sessions += newSess
        SessionCreated('token -> ut, 'userid -> u.login, 'authkey -> newSess.securityToken)
        newSess
      case Some(s) =>
        addUserToken(s, ut)
        s
    }
    sess
  }

  private def authenticateWithCredentials(v: String, ut: String): Option[SignalResponse] =
    for (
      j <- Some(Json.parse(v));
      l <- j ~> 'l;
      p <- j ~> 'p;
      u <- user(l) if u.hash == hashFor(p)
    ) yield {
      val sess = createSession(ut, u)
      publishAllForUserToken(ut)
      SuccessfulCredentialsAuth('token -> ut, 'authkey -> v, 'userid -> sess.userId)
      SignalOk(Some(true))
    }

  private def authenticateWithToken(v: String, ut: String): Option[SignalResponse] =
    for (
      j <- Some(Json.parse(v));
      t <- j ~> 't;
      sess <- sessions.find(_.securityToken == t)
    ) yield {
      addUserToken(sess, ut)
      publishAllForUserToken(ut)
      SuccessfulTokenAuth('token -> ut, 'authkey -> v, 'userid -> sess.userId)
      SignalOk(Some(true))
    }

}
