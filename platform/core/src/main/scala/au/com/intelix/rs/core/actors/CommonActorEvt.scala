package au.com.intelix.rs.core.actors

import au.com.intelix.evt.{InfoE, WarningE}

object CommonActorEvt {

  object Evt {
    case object PostStop extends InfoE
    case object PreStart extends InfoE
    case object PreRestart extends WarningE
    case object PostRestart extends WarningE
    case object StateTransition extends InfoE
  }

}
