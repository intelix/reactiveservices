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
package rs.service.auth.ref

import rs.core.actors.StatelessActor
import rs.core.config.ConfigOps.wrap
import rs.service.auth.api.AuthenticationMessages.{AuthenticationResponse, Authenticate}

class ConfigBasedAuthenticationProviderActor extends StatelessActor with ConfigBasedAuthenticationProviderEvt {

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
