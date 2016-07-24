package au.com.intelix.rs.core.actors

import akka.actor.SupervisorStrategy.{Decider, Restart, Stop}
import akka.actor._
import au.com.intelix.evt.{CommonEvt, EvtSource, EvtContext}

class RootSupervisorStrategy extends SupervisorStrategyConfigurator {

  implicit val evtCtx = new EvtContext {
    override val evtSource: EvtSource = "RootSupervisor"
  }

  final val decider: Decider = {
    case x: ActorInitializationException =>
      evtCtx.raise(CommonEvt.EvtSupervisorStopTrigger, 'Message -> x.getMessage, 'Trace -> x.getCause)
      Stop
    case x: ActorKilledException =>
      evtCtx.raise(CommonEvt.EvtSupervisorStopTrigger, 'Message -> x.getMessage, 'Trace -> x.getCause)
      Stop
    case x: DeathPactException =>
      evtCtx.raise(CommonEvt.EvtSupervisorStopTrigger, 'Message -> x.getMessage, 'Trace -> x.getCause)
      Stop
    case x: Exception =>
      evtCtx.raise(CommonEvt.EvtSupervisorRestartTrigger, 'Message -> x.getMessage, 'Trace -> x.getCause)
      Restart
  }

  override def create(): SupervisorStrategy = OneForOneStrategy(loggingEnabled = false)(decider)

}

