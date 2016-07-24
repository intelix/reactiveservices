/*
 * Copyright 2014-16 Intelix Pty Ltd
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
import au.com.intelix.essentials.json.JsonTools
import play.api.libs.json.Json
import au.com.intelix.rs.core.SubjectTags.{UserId, UserToken}
import au.com.intelix.config.ConfigOps._
import au.com.intelix.evt.{EvtSource, InfoE, TraceE}
import au.com.intelix.rs.core.services.{CompoundStreamIdTemplate, StatelessServiceActor}
import au.com.intelix.rs.core.stream.DictionaryMapStreamState.Dictionary
import au.com.intelix.rs.core.stream.SetStreamState.SetSpecs
import au.com.intelix.essentials.json.JsonTools.jsToExtractorOps
import au.com.intelix.rs.core.{Subject, TopicKey}
import rs.service.auth.AuthServiceActor.InfoUserId
import rs.service.auth.api.AuthenticationMessages.{Authenticate, AuthenticationResponse, Invalidate}
import rs.service.auth.api.AuthorisationMessages.{DomainPermissions, PermissionsRequest, PermissionsRequestCancel, SubjectPermissions}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps


object AuthServiceActor {
  val InfoUserId = "id"
  val InfoUsername = "uname"
  val InfoTags = "tags"


  case object EvtAuthToken extends TraceE

  case object EvtUserInfo extends TraceE

  case object EvtUserDomainPermissions extends TraceE

  case object EvtUserSubjectsPermissions extends TraceE

  case object EvtUserTokenAdded extends InfoE

  case object EvtSessionCreated extends InfoE

  case object EvtUserSessionExpired extends InfoE

  case object EvtUserSessionInvalidated extends InfoE

  case object EvtAuthRequest extends InfoE

  case object EvtSuccessfulCredentialsAuth extends InfoE

  case object EvtSuccessfulTokenAuth extends InfoE

  case object EvtFailedCredentialsAuth extends InfoE

  case object EvtFailedTokenAuth extends InfoE

  val EvtSourceId = "Auth"


}

class AuthServiceActor extends StatelessServiceActor {

  import AuthServiceActor._

  implicit val specs = SetSpecs(allowPartialUpdates = true)
  implicit val infoDict = Dictionary(InfoUserId, InfoUsername, InfoTags)


  val authenticationProviderRef = context.actorOf(serviceCfg.asRequiredProps("authentication-provider"), "authentication-provider")
  val authenticationProviderTimeout = serviceCfg.asFiniteDuration("authentication-provider-timeout", 10 seconds)

  val authorisationProviderRef = context.actorOf(serviceCfg.asRequiredProps("authorisation-provider"), "authorisation-provider")
  val authorisationProviderTimeout = serviceCfg.asFiniteDuration("authorisation-provider-timeout", 10 seconds)

  val SessionTimeout = serviceCfg.asFiniteDuration("idle-session-timeout", 5 seconds)
  var sessions: Map[Int, Session] = Map()

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
      raise(EvtAuthRequest, 'token -> ut)
      parseCredentials(body) match {
        case None =>
          raise(EvtFailedCredentialsAuth, 'reason -> "invalid request")
          Future.successful(SignalOk(false))
        case Some((user, pass)) =>
          authenticateWithCredentials(user, pass, ut) map {
            case (Some(userId), tags) =>
              self ! AuthOk(ut, user, userId, tags)
              SignalOk(true)
            case _ => self ! AuthFailed(ut, user)
              SignalOk(false)
          }
      }

  }

  onSignal {
    case (Subject(_, TopicKey("invalidate"), UserToken(ut)), _) =>
      sessionByUserToken(ut) foreach invalidateSession
      SignalOk()
    case (Subject(_, TopicKey("tauth"), UserToken(ut)), securityToken: String) =>
      raise(EvtAuthRequest, 'token -> ut, 'authkey -> securityToken)
      authenticateWithToken(securityToken, ut) match {
        case Some(sess) =>
          raise(EvtSuccessfulTokenAuth, 'token -> ut, 'authkey -> securityToken, 'user -> sess.user, 'userid -> sess.userId)
          publishAllForUserToken(ut)
          SignalOk(true)
        case _ =>
          raise(EvtFailedTokenAuth, 'token -> ut, 'authkey -> securityToken, 'reason -> "access denied")
          SignalOk(false)
      }

  }


  defaultSignalResponseFor {
    case _ => SignalFailed()
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
    case AuthOk(ut, user, userId, tags) =>
      val sess = createSession(ut, user, userId, tags)
      raise(EvtSuccessfulCredentialsAuth, 'token -> ut, 'authkey -> sess.securityToken, 'user -> user, 'userid -> userId, 'tags -> tags)
      publishAllForUserToken(ut)
    case AuthFailed(ut, user) =>
      raise(EvtFailedCredentialsAuth, 'token -> ut, 'user -> user, 'reason -> "access denied")
  }


  onTick {
    sessionsHousekeeping()
  }

  def invalidateUser(userId: Int) = sessionByUserId(userId) foreach invalidateSession

  def invalidateSession(sess: Session) = {
    authorisationProviderRef ! PermissionsRequestCancel(sess.userId)
    sessions -= sess.userId
    sess.userTokens foreach publishAllForUserToken
    raise(EvtUserSessionInvalidated, 'user -> sess.user, 'userid -> sess.userId, 'authkey -> sess.securityToken)
  }

  def sessionsHousekeeping() = sessions = sessions filter {
    case (_, s@Session(ut, uname, uid, tags, at, Some(t), _, _)) if now - t > SessionTimeout.toMillis =>
      raise(EvtUserSessionExpired, 'user -> uname, 'userid -> uid, 'authkey -> at)
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
    raise(EvtAuthToken, 'token -> userToken, 'authkey -> securityToken)
  }

  def publishInfo(userToken: String): Unit = {
    val username = sessionByUserToken(userToken).map(_.user).getOrElse("")
    val userId = sessionByUserToken(userToken).map(_.userId).getOrElse(0)
    val tags = sessionByUserToken(userToken).flatMap(_.tags).getOrElse("")
    InfoStream(userToken) !# (InfoUserId -> userId, InfoUsername -> username, InfoTags -> tags)
    raise(EvtUserInfo, 'token -> userToken, 'user -> username, 'userid -> userId, 'tags -> tags)
  }

  def publishDomainPermissions(userToken: String): Unit = {
    val set = sessionByUserToken(userToken).map(_.domains).getOrElse(Set())
    DomainPermissionsStream(userToken) !% set
    raise(EvtUserDomainPermissions, 'token -> userToken, 'set -> set)
  }

  def publishSubjectPermissions(userToken: String): Unit = {
    val set = sessionByUserToken(userToken).map(_.subjectPatterns).getOrElse(Set())
    SubjectPermissionsStream(userToken) !% set
    raise(EvtUserSubjectsPermissions, 'token -> userToken, 'set -> set)
  }

  def sessionByUserToken(userToken: String) = sessions.values.find(_.userTokens.contains(userToken))

  def sessionByUserId(id: Int) = sessions get id

  def createSession(ut: String, user: String, userId: Int, tags: Option[String]): Session =
    sessionByUserToken(ut) orElse sessionByUserId(userId) match {
      case None =>
        val newSess = Session(Set(ut), user, userId, tags, randomUUID, None)
        sessions += userId -> newSess
        authorisationProviderRef ! PermissionsRequest(userId)
        raise(EvtSessionCreated, 'token -> ut, 'user -> user, 'userid -> userId, 'authkey -> newSess.securityToken, 'tags -> tags)
        newSess
      case Some(s) => addUserToken(s, ut)
    }

  def addUserToken(session: Session, ut: String) = {
    val newSess = session.copy(userTokens = session.userTokens + ut, idleSince = None)
    sessions += session.userId -> newSess
    raise(EvtUserTokenAdded, 'token -> ut, 'user -> newSess.user, 'userid -> newSess.userId, 'authkey -> newSess.securityToken)
    newSess
  }


  def parseCredentials(json: String) = for (
    parsedJson <- Some(Json.parse(json));
    user <- parsedJson ~> 'u;
    pass <- parsedJson ~> 'p
  ) yield (user, pass)

  def authenticateWithCredentials(user: String, pass: String, ut: String): Future[(Option[Int], Option[String])] =
    ask(authenticationProviderRef, Authenticate(user, pass), authenticationProviderTimeout)
      .map {
        case AuthenticationResponse(r, keys) => (r, keys)
        case _ => (None, None)
      }

  def authenticateWithToken(token: String, ut: String): Option[Session] =
    sessions.values.find(_.securityToken == token) map { sess => addUserToken(sess, ut) }


  case class CachedResponse(allow: Boolean, expireTs: Long) {
    def isExpired = System.currentTimeMillis() <= expireTs
  }

  case class Session(userTokens: Set[String],
                     user: String,
                     userId: Int,
                     tags: Option[String],
                     securityToken: String,
                     idleSince: Option[Long],
                     domains: Set[String] = Set(),
                     subjectPatterns: Set[String] = Set())

  case class AuthOk(ut: String, user: String, userId: Int, tags: Option[String])

  case class AuthFailed(ut: String, user: String)

  override val evtSource: EvtSource = EvtSourceId
}
