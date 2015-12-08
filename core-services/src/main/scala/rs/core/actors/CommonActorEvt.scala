package rs.core.actors

import rs.core.sysevents.ref.ComponentWithBaseSysevents

trait CommonActorEvt extends ComponentWithBaseSysevents {
  val PostStop = "Lifecycle.PostStop".info
  val PreStart = "Lifecycle.PreStart".info
  val PreRestart = "Lifecycle.PreRestart".info
  val PostRestart = "Lifecycle.PostRestart".info
  val StateTransition = "Lifecycle.StateTransition".info
  val StateChange = "StateChange".info
}
