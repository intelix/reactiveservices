package rs.service.auth

import play.api.libs.json.Json
import rs.core.SubjectKeys.UserToken
import rs.core.actors.{ActorWithTicks, BaseActorSysevents}
import rs.core.services.ServiceCell
import rs.core.services.internal.StringStreamRef2
import rs.core.stream.{SetStreamPublisher, StringStreamPublisher}
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.core.tools.Tools.configHelper
import rs.core.{ServiceKey, Subject, TopicKey}
import rs.service.auth.UserAuthenticationActor._
import rs.core.sysevents.SyseventOps._

import scala.concurrent.duration._
import scala.language.postfixOps


trait UserAuthenticationSysevents extends ComponentWithBaseSysevents with BaseActorSysevents {
  val UserTokenSubscription = "UserTokenSubscription".trace
  val AuthToken = "AuthToken".trace
  val UserInfo = "UserInfo".trace
  val UserPermissions = "UserPermissions".trace
  val UserTokenInvalidated = "UserTokenInvalidated".info
  val UserTokenAdded = "UserTokenAdded".info
  val SessionCreated = "SessionCreated".info
  val UserSessionExpired = "UserSessionExpired".info
  val SuccessfulCredentialsLoginRequest = "SuccessfulCredentialsLoginRequest".info
  val SuccessfulTokenLoginRequest = "SuccessfulTokenLoginRequest".info
  val FailedCredentialsLoginRequest = "FailedCredentialsLoginRequest".info
  val FailedTokenLoginRequest = "FailedTokenLoginRequest".info

  override def componentId: String = "UserAuth"
}


object UserAuthenticationActor extends UserAuthenticationSysevents {
  val serviceId = "user_auth"

  case class User(login: String, hash: String, permissions: Set[String])

  case class Session(userTokens: Set[String], userId: String, securityToken: String, idleSince: Option[Long])

}

abstract class UserAuthenticationActor(id: String)
  extends ServiceCell(id)
  with StringStreamPublisher
  with SetStreamPublisher
  with ActorWithTicks
  with UserAuthenticationSysevents {


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


  private val SessionTimeout = 5 minutes

  private var sessions: Set[Session] = Set.empty

  override def processTick(): Unit = {
    super.processTick()
    invalidateSessions()
  }

  // TODO do properly
  private var permByUser: Map[String, Set[String]] = Map.empty

  def user(userId: String): Option[User]

  def tokenStream(ut: String) = StringStreamRef2("t", ut)

  def permissionsStream(ut: String) = StringStreamRef2("p", ut)

  def infoStream(ut: String) = StringStreamRef2("i", ut)

  def sessionByUserToken(userToken: String) = sessions.find(_.userTokens.contains(userToken))

  def sessionByUserId(id: String) = sessions.find(_.userId == id)

  def publishToken(userToken: String): Unit = {
    val securityToken = sessionByUserToken(userToken) match {
      case Some(s) => s.securityToken
      case _ => ""
    }
    tokenStream(userToken) !~ securityToken
    //    println(s"!>>>>> token $securityToken published to ${tokenTopic(userToken)}")
    AuthToken >>('UserToken -> userToken, 'AuthToken -> securityToken)
  }

  def publishInfo(userToken: String): Unit = {
    val info = sessionByUserToken(userToken) match {
      case Some(s) => s.userId
      case _ => ""
    }
    infoStream(userToken) !~ info
    UserInfo >>('UserToken -> userToken, 'Info -> info)
  }

  def publishPermissions(userToken: String): Unit = {
    val permissions = sessionByUserToken(userToken) match {
      case Some(s) => user(s.userId) match {
        case Some(u) => u.permissions
        case None => Set.empty[String]
      }
      case _ => Set.empty[String]
    }
    permissionsStream(userToken) !% permissions
    UserPermissions >>('UserToken -> userToken, 'Permissions -> permissions.mkString(";"))
  }


  def removeUserToken(userToken: String): Unit = {
    sessions map {
      case s if s.userTokens.contains(userToken) => s.copy(userTokens = s.userTokens - userToken)
      case s => s
    }
    UserTokenInvalidated >> ('UserToken -> userToken)
  }

  def invalidateSessions() = sessions = sessions filter {
    case Session(ut, uid, at, Some(t)) if now - t > SessionTimeout.toMillis =>
      UserSessionExpired >>('UserId -> uid, 'AuthToken -> at)
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
      UserTokenAdded >>('UserToken -> ut, 'UserId -> x.userId, 'AuthToken -> x.securityToken)
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
        SessionCreated >>('UserToken -> ut, 'UserId -> u.login, 'AuthToken -> newSess.securityToken)
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
      SuccessfulCredentialsLoginRequest >>('UserToken -> ut, 'AuthToken -> v, 'UserId -> sess.userId)
      SignalOk(Some("Login successful"))
    }

  private def authenticateWithToken(v: String, ut: String): Option[SignalResponse] =
    for (
      j <- Some(Json.parse(v));
      t <- j ~> 't;
      sess <- sessions.find(_.securityToken == t)
    ) yield {
      addUserToken(sess, ut)
      publishAllForUserToken(ut)
      SuccessfulTokenLoginRequest >>('UserToken -> ut, 'AuthToken -> v, 'UserId -> sess.userId)
      SignalOk(None)
    }

  onSignal {
    case (Subject(_, TopicKey("authenticate"), UserToken(ut)), v: String) =>
      authenticateWithCredentials(v, ut) orElse authenticateWithToken(v, ut) orElse {
        FailedCredentialsLoginRequest >> ('Login -> (Json.parse(v) ~> 'l))
        Some(SignalOk(Some("Authentication failed")))
      }
  }

  onStreamActive {
    case StringStreamRef2("t", ut) => publishToken(ut)
    case StringStreamRef2("i", ut) => publishInfo(ut)
    case StringStreamRef2("p", ut) => publishPermissions(ut)
  }

  subjectToStreamKey {
    case Subject(_, TopicKey("token"), UserToken(ut)) => tokenStream(ut)
    case Subject(_, TopicKey("permissions"), UserToken(ut)) => permissionsStream(ut)
    case Subject(_, TopicKey("info"), UserToken(ut)) => infoStream(ut)
  }

  override def serviceKey: ServiceKey = UserAuthenticationActor.serviceId
}
