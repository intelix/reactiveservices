package rs.service.auth.ref

import rs.core.actors.BaseActorSysevents

trait ConfigBasedAuthenticationProviderEvt extends BaseActorSysevents {

  val Authentication = "Authentication".info

  override def componentId: String = "ConfigBasedAuthenticationProvider"
}

object ConfigBasedAuthenticationProviderEvt extends ConfigBasedAuthenticationProviderEvt
