package rs.service.auth

import play.api.libs.json.Json
import rs.core.SubjectKeys.{UserToken, KeyOps}
import rs.core.tools.Tools.configHelper
import rs.core.{TopicKey, Subject}
import rs.core.services.FSMServiceCell
import rs.service.auth.AuthServiceActor.Data

object AuthServiceActor {

  case class Data()

}

class AuthServiceActor(id: String) extends FSMServiceCell[Data](id) with BaseAuthEvt {

//  onSignal {
//    case (Subject(_, TopicKey("authenticate"), UserToken(ut)), v: String) =>
//      AuthRequest { ctx =>
//        ctx + ('token -> ut)
//        authenticateWithCredentials(v, ut) orElse authenticateWithToken(v, ut) orElse {
//          FailedCredentialsAuth('login -> (Json.parse(v) ~> 'l))
//          Some(SignalOk(Some(false)))
//        }
//      }
//  }


  private def authenticateWithCredentials(v: String, ut: String): Option[Boolean] = ???
  private def authenticateWithToken(v: String, ut: String): Option[Boolean] = ???

}
