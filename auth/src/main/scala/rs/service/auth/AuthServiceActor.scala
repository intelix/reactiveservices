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

import akka.pattern.Patterns.ask
import play.api.libs.json.Json
import rs.core.SubjectTags.UserToken
import rs.core.config.ConfigOps.wrap
import rs.core.services.{CompoundStreamIdTemplate, StatelessServiceActor}
import rs.core.stream.DictionaryMapStreamState.Dictionary
import rs.core.stream.SetStreamState.SetSpecs
import rs.core.tools.JsonTools.jsToExtractorOps
import rs.core.{Subject, TopicKey}
import rs.service.auth.AuthServiceActor.InfoUserId
import rs.service.auth.api.AuthenticationMessages.{Authenticate, AuthenticationResponse, Invalidate}
import rs.service.auth.api.AuthorisationMessages.{DomainPermissions, PermissionsRequest, PermissionsRequestCancel, SubjectPermissions}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps


object AuthServiceActor {
  val InfoUserId = "id"
}

class AuthServiceActor(id: String) extends StatelessServiceActor(id) with AuthServiceEvt {

  implicit val specs = SetSpecs(allowPartialUpdates = true)
  implicit val infoDict = Dictionary(InfoUserId)


  val authenticationProviderRef = context.actorOf(serviceCfg.asRequiredProps("authentication-provider"), "authentication-provider")
  val authenticationProviderTimeout = serviceCfg.asFiniteDuration("authentication-provider-timeout", 10 seconds)

  val authorisationProviderRef = context.actorOf(serviceCfg.asRequiredProps("authorisation-provider"), "authorisation-provider")
  val authorisationProviderTimeout = serviceCfg.asFiniteDuration("authorisation-provider-timeout", 10 seconds)

  val SessionTimeout = serviceCfg.asFiniteDuration("idle-session-timeout", 5 seconds)
  var sessions: Map[String, Session] = Map()

  object TokenStream extends CompoundStreamIdTemplate[String]("t")

  object InfoStream extends CompoundStreamIdTemplate[String]("i")

  object DomainPermissionsStream extends CompoundStreamIdTemplate[String]("d")

  object SubjectPermissionsStream extends CompoundStreamIdTemplate[String]("s")

  onSubjectMapping {
    case Subject(_, TopicKey("token"), UserToken(ut)) => TokenStream(ut)
    case Subject(_, TopicKey("info"), UserToken(ut)) => InfoStream(ut)
    case Subject(_, TopicKey("domains"), UserToken(ut)) => DomainPermissionsStream(ut)
    case Subject(_, TopicKey("subjects"), UserToken(ut)) => SubjectPermissionsStream(ut)
  }

  onStreamActive {
    case TokenStream(ut) => publishToken(ut)
    case InfoStream(ut) => publishInfo(ut)
    case DomainPermissionsStream(ut) => publishDomainPermissions(ut)
    case SubjectPermissionsStream(ut) => publishSubjectPermissions(ut)
  }

  onSignalAsync {
    case (Subject(_, TopicKey("cauth"), UserToken(ut)), body: String) =>
      AuthRequest { ctx =>
        ctx + ('token -> ut)
        parseCredentials(body) match {
          case None =>
            FailedCredentialsAuth('reason -> "invalid request")
            Future.successful(SignalOk(false))
          case Some((user, pass)) =>
            authenticateWithCredentials(user, pass, ut) map {
              case true =>
                self ! AuthOk(ut, user)
                SignalOk(true)
              case _ => self ! AuthFailed(ut, user)
                SignalOk(false)
            }
        }
      }
  }

  onSignal {
    case (Subject(_, TopicKey("tauth"), UserToken(ut)), securityToken: String) =>
      AuthRequest { ctx =>
        ctx +('token -> ut, 'authkey -> securityToken)
        authenticateWithToken(securityToken, ut) match {
          case Some(sess) =>
            SuccessfulTokenAuth('token -> ut, 'authkey -> securityToken, 'userid -> sess.user)
            publishAllForUserToken(ut)
            SignalOk(true)
          case _ =>
            FailedTokenAuth('token -> ut, 'authkey -> securityToken, 'reason -> "access denied")
            SignalOk(false)
        }
      }
  }


  onMessage {
    case Invalidate(user) => invalidateUser(user)
    case DomainPermissions(user, set) =>
      sessions get user foreach { sess =>
        sessions += user -> sess.copy(domains = set)
        sess.userTokens foreach publishDomainPermissions
      }
    case SubjectPermissions(user, set) =>
      sessions get user foreach { sess =>
        sessions += user -> sess.copy(subjectPatterns = set)
        sess.userTokens foreach publishSubjectPermissions
      }
    case AuthOk(ut, user) =>
      val sess = createSession(ut, user)
      SuccessfulCredentialsAuth('token -> ut, 'authkey -> sess.securityToken, 'userid -> user)
      publishAllForUserToken(ut)
    case AuthFailed(ut, user) =>
      FailedCredentialsAuth('token -> ut, 'userid -> user, 'reason -> "access denied")
  }


  onTick {
    sessionsHousekeeping()
  }

  def invalidateUser(user: String) = sessionByUserId(user) foreach invalidateSession

  def invalidateSession(sess: Session) = {
    authorisationProviderRef ! PermissionsRequestCancel(sess.user)
    sessions -= sess.user
    sess.userTokens foreach publishAllForUserToken
    UserSessionInvalidated('userid -> sess.user, 'authkey -> sess.securityToken)
  }

  def sessionsHousekeeping() = sessions = sessions filter {
    case (_, s@Session(ut, uid, at, Some(t), _, _)) if now - t > SessionTimeout.toMillis =>
      UserSessionExpired('userid -> uid, 'authkey -> at)
      authorisationProviderRef ! PermissionsRequestCancel(uid)
      false
    case _ => true
  } map {
    case (k, s) if s.userTokens.isEmpty => k -> s.copy(idleSince = Some(now))
    case x => x
  }


  def publishAllForUserToken(ut: String) = {
    publishInfo(ut)
    publishToken(ut)
    publishDomainPermissions(ut)
    publishSubjectPermissions(ut)
  }

  def publishToken(userToken: String): Unit = {
    val securityToken = sessionByUserToken(userToken).map(_.securityToken).getOrElse("")
    TokenStream(userToken) !~ securityToken
    AuthToken('token -> userToken, 'authkey -> securityToken)
  }

  def publishInfo(userToken: String): Unit = {
    val id = sessionByUserToken(userToken).map(_.user).getOrElse("")
    InfoStream(userToken) !# (InfoUserId -> id)
    UserInfo('token -> userToken, 'userid -> id)
  }

  def publishDomainPermissions(userToken: String): Unit = {
    val set = sessionByUserToken(userToken).map(_.domains).getOrElse(Set())
    DomainPermissionsStream(userToken) !% set
    UserDomainPermissions('token -> userToken, 'userid -> id, 'set -> set)
  }

  def publishSubjectPermissions(userToken: String): Unit = {
    val set = sessionByUserToken(userToken).map(_.subjectPatterns).getOrElse(Set())
    SubjectPermissionsStream(userToken) !% set
    UserSubjectsPermissions('token -> userToken, 'userid -> id, 'set -> set)
  }

  def sessionByUserToken(userToken: String) = sessions.values.find(_.userTokens.contains(userToken))

  def sessionByUserId(id: String) = sessions get id

  def createSession(ut: String, user: String): Session =
    sessionByUserToken(ut) orElse sessionByUserId(user) match {
      case None =>
        val newSess = Session(Set(ut), user, randomUUID, None)
        sessions += user -> newSess
        authorisationProviderRef ! PermissionsRequest(user)
        SessionCreated('token -> ut, 'userid -> user, 'authkey -> newSess.securityToken)
        newSess
      case Some(s) => addUserToken(s, ut)
    }

  def addUserToken(session: Session, ut: String) = {
    val newSess = session.copy(userTokens = session.userTokens + ut, idleSince = None)
    sessions += session.user -> newSess
    UserTokenAdded('token -> ut, 'userid -> newSess.user, 'authkey -> newSess.securityToken)
    newSess
  }


  def parseCredentials(json: String) = for (
    parsedJson <- Some(Json.parse(json));
    user <- parsedJson ~> 'u;
    pass <- parsedJson ~> 'p
  ) yield (user, pass)

  def authenticateWithCredentials(user: String, pass: String, ut: String): Future[Boolean] =
    ask(authenticationProviderRef, Authenticate(user, pass), authenticationProviderTimeout)
      .map {
        case AuthenticationResponse(r) => r
        case _ => false
      }

  def authenticateWithToken(token: String, ut: String): Option[Session] =
    sessions.values.find(_.securityToken == token) map { sess => addUserToken(sess, ut) }


  case class CachedResponse(allow: Boolean, expireTs: Long) {
    def isExpired = System.currentTimeMillis() <= expireTs
  }

  case class Session(
                      userTokens: Set[String],
                      user: String,
                      securityToken: String,
                      idleSince: Option[Long],
                      domains: Set[String] = Set(),
                      subjectPatterns: Set[String] = Set())

  case class AuthOk(ut: String, user: String)

  case class AuthFailed(ut: String, user: String)

}
