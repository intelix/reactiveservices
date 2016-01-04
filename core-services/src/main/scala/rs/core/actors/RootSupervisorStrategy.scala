package rs.core.actors

import akka.actor.SupervisorStrategy.{Decider, Restart, Stop}
import akka.actor._
import rs.core.sysevents.{CommonEvt, EvtPublisher}

class RootSupervisorStrategy extends SupervisorStrategyConfigurator with CommonEvt {

  implicit val pub = EvtPublisher()

  final val decider: Decider = {
    case x: ActorInitializationException =>
      SupervisorStopTrigger('Message -> x.getMessage, 'Trace -> x.getCause)
      Stop
    case x: ActorKilledException =>
      SupervisorStopTrigger('Message -> x.getMessage, 'Trace -> x.getCause)
      Stop
    case x: DeathPactException =>
      SupervisorStopTrigger('Message -> x.getMessage, 'Trace -> x.getCause)
      Stop
    case x: Exception =>
      SupervisorRestartTrigger('Message -> x.getMessage, 'Trace -> x.getCause)
      Restart
  }

  override def create(): SupervisorStrategy = OneForOneStrategy(loggingEnabled = false)(decider)

  override def componentId: String = "RootSupervisor"
}

