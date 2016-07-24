package au.com.intelix.rs.core.actors

import au.com.intelix.evt.InfoE

object CommonActorEvt {

  case object EvtPostStop extends InfoE

  case object EvtPreStart extends InfoE

  case object EvtPreRestart extends InfoE

  case object EvtPostRestart extends InfoE

  case object EvtStateTransition extends InfoE

  case object EvtStateChange extends InfoE

}
