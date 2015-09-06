package rs.service.auth

import play.api.libs.json.Json
import rs.core.SubjectKeys.UserToken
import rs.core.actors.{ActorWithTicks, BaseActorSysevents}
import rs.core.services.ServiceCell
import rs.core.services.internal.CompositeStreamId
import rs.core.stream.SetStreamState.SetSpecs
import rs.core.stream.{SetStreamPublisher, StringStreamPublisher}
import rs.core.tools.Tools.configHelper
import rs.core.{Subject, TopicKey}
import rs.service.auth.UserAuthenticationActor.{Session, User}
import rs.core.sysevents.SyseventOps._

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
  val SuccessfulCredentialsLoginRequest = "SuccessfulCredentialsLoginRequest".info
  val SuccessfulTokenLoginRequest = "SuccessfulTokenLoginRequest".info
  val FailedCredentialsLoginRequest = "FailedCredentialsLoginRequest".info
  val FailedTokenLoginRequest = "FailedTokenLoginRequest".info

  override def componentId: String = "Auth"

}


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

  def tokenStream(ut: String) = CompositeStreamId("t", ut)

  def permissionsStream(ut: String) = CompositeStreamId("p", ut)

  def infoStream(ut: String) = CompositeStreamId("i", ut)

  def sessionByUserToken(userToken: String) = sessions.find(_.userTokens.contains(userToken))

  def sessionByUserId(id: String) = sessions.find(_.userId == id)

  def publishToken(userToken: String): Unit = {
    val securityToken = sessionByUserToken(userToken) match {
      case Some(s) => s.securityToken
      case _ => ""
    }
    tokenStream(userToken) !~ securityToken
    //    println(s"!>>>>> token $securityToken published to ${tokenTopic(userToken)}")
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

  def publishPermissions(userToken: String): Unit = {
    UserPermissions { ctx =>
      val permissions = sessionByUserToken(userToken) match {
        case Some(s) => user(s.userId) match {
          case Some(u) => u.permissions
          case None => Set.empty[String]
        }
        case _ => Set.empty[String]
      }
      ctx + ('token -> userToken)
      ctx + ('perm -> permissions.mkString(";"))
      permissionsStream(userToken) !% permissions
    }
    //    UserPermissions >>('token -> userToken, 'perm -> permissions.mkString(";"))
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
      SuccessfulCredentialsLoginRequest('token -> ut, 'authkey -> v, 'userid -> sess.userId)
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
      SuccessfulTokenLoginRequest('token -> ut, 'authkey -> v, 'userid -> sess.userId)
      SignalOk(Some(true))
    }

  onSignal {
    case (Subject(_, TopicKey("authenticate"), UserToken(ut)), v: String) =>
      println(s"!>>>>> SIGNALx: " + v)
      authenticateWithCredentials(v, ut) orElse authenticateWithToken(v, ut) orElse {
        FailedCredentialsLoginRequest('login -> (Json.parse(v) ~> 'l))
        Some(SignalOk(Some(false)))
      }
    case (a,b) =>
      println(s"!>>>>> SIGNAL2: " + a +" : " + b)
      None
  }

  onStreamActive {
    case CompositeStreamId("t", ut) => publishToken(ut)
    case CompositeStreamId("i", ut) => publishInfo(ut)
    case CompositeStreamId("p", ut) => publishPermissions(ut)
  }

  onSubject {
    case Subject(_, TopicKey("token"), UserToken(ut)) =>
      println(s"!>>>> $ut subscribed to token")
      Some(tokenStream(ut))
    case Subject(_, TopicKey("permissions"), UserToken(ut)) => Some(permissionsStream(ut))
    case Subject(_, TopicKey("info"), UserToken(ut)) => Some(infoStream(ut))
  }
}
