package rs.service.auth.ref

import rs.core.actors.BaseActorSysevents


trait ConfigBasedAuthorisationProviderEvt extends BaseActorSysevents {

  override def componentId: String = "ConfigBasedAuthorisationProvider"
}

object ConfigBasedAuthorisationProviderEvt extends ConfigBasedAuthorisationProviderEvt
