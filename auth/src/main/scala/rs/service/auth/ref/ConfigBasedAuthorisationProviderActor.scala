package rs.service.auth.ref

import rs.core.actors.SingleStateActor
import rs.core.config.ConfigOps.wrap
import rs.service.auth.api.AuthorisationMessages.{DomainPermissions, PermissionsRequest, SubjectPermissions}

class ConfigBasedAuthorisationProviderActor extends SingleStateActor with ConfigBasedAuthorisationProviderEvt {

  onMessage {
    case PermissionsRequest(u) =>
      sender() ! DomainPermissions(u, config.asStringList("users." + u + ".domains").toSet)
      sender() ! SubjectPermissions(u, config.asStringList("users." + u + ".subjects").toSet)

  }

}
