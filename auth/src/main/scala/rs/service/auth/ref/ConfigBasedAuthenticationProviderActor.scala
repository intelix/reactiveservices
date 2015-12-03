package rs.service.auth.ref

import rs.core.actors.SingleStateActor
import rs.core.config.ConfigOps.wrap
import rs.service.auth.api.AuthenticationMessages.{AuthenticationResponse, Authenticate}

class ConfigBasedAuthenticationProviderActor extends SingleStateActor with ConfigBasedAuthenticationProviderEvt {

  onMessage {
    case Authenticate(u, p) => Authentication { ctx =>
      ctx + ('user -> u)
      config asOptString ("users." + u + ".passw") match {
        case Some(h) if hashFor(p) == h =>
          ctx + ('allowed -> true)
          sender() ! AuthenticationResponse(true)
        case _ =>
          ctx + ('allowed -> false, 'provided -> hashFor(p))
          sender() ! AuthenticationResponse(false)
      }
    }
  }

  def hashFor(s: String): String = {
    val m = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))
    m.map("%02x".format(_)).mkString
  }

}
