package rs.core.actors

import rs.core.evt.InfoE

object CommonActorEvt {

  case object EvtPostStop extends InfoE

  case object EvtPreStart extends InfoE

  case object EvtPreRestart extends InfoE

  case object EvtPostRestart extends InfoE

  case object EvtStateTransition extends InfoE

  case object EvtStateChange extends InfoE

}
