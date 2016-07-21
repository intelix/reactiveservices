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
package rs.service.auth.configbased

import rs.core.actors.StatelessActor
import rs.core.config.ConfigOps.wrap
import rs.core.evt.{EvtSource, InfoE}
import rs.service.auth.api.AuthenticationMessages.{Authenticate, AuthenticationResponse}

object ConfigBasedAuthenticationProviderActor {

  case object Authentication extends InfoE

  val EvtSourceId = "Auth.AuthenticationProvider"
}

class ConfigBasedAuthenticationProviderActor extends StatelessActor {

  import ConfigBasedAuthenticationProviderActor._

  addEvtFields('type -> "config-based")

  onMessage {
    case Authenticate(u, p) =>
      config asOptString ("users." + u + ".passw") match {
        case Some(h) if hashFor(p) == h =>
          raise(Authentication, 'user -> u, 'allowed -> true)
          sender() ! AuthenticationResponse(config.asOptInt("users." + u + ".id"))
        case _ =>
          raise(Authentication, 'user -> u, 'allowed -> false, 'provided -> hashFor(p))
          sender() ! AuthenticationResponse(None)
      }
  }

  def hashFor(s: String): String = {
    val m = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))
    m.map("%02x".format(_)).mkString
  }

  override val evtSource: EvtSource = EvtSourceId
}
