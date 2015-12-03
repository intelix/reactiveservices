package rs.service.auth.ref

import rs.core.actors.SingleStateActor
import rs.core.config.ConfigOps.wrap
import rs.service.auth.api.AuthorisationMessages.{TopicPermissions, DomainPermissions, PermissionsRequest}

class ConfigBasedAuthorisationProviderActor extends SingleStateActor with ConfigBasedAuthorisationProviderEvt {

  onMessage {
    case PermissionsRequest(u) =>
      sender() ! DomainPermissions(u, config.asStringList("users." + u + ".domains").toSet)
      println("!>>>>> " + config.asStringList("users." + u + ".topics").toSet)
      sender() ! TopicPermissions(u, config.asStringList("users." + u + ".topics").toSet)
  }

}
