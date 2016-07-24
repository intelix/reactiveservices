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

import au.com.intelix.rs.core.actors.StatelessActor
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.evt.{EvtSource, InfoE}
import rs.service.auth.api.AuthorisationMessages.{DomainPermissions, PermissionsRequest, SubjectPermissions}

object ConfigBasedAuthorisationProviderActor {

  case object Authorisation extends InfoE

  val EvtSourceId = "Auth.AuthorisationProvider"
}

class ConfigBasedAuthorisationProviderActor extends StatelessActor {

  import ConfigBasedAuthorisationProviderActor._

  addEvtFields('type -> "config-based")

  onMessage {
    case PermissionsRequest(u) =>
      raise(Authorisation, 'user -> u)
      sender() ! DomainPermissions(u, config.asStringList("users." + u + ".domains").toSet)
      sender() ! SubjectPermissions(u, config.asStringList("users." + u + ".subjects").toSet)
  }

  override val evtSource: EvtSource = EvtSourceId
}
