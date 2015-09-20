package rs.core

import rs.core.actors.{BaseActorSysevents, ClusterAwareness}
import rs.core.services.{StreamId, ServiceCellSysevents, ServiceCell}
import rs.core.stream.StringStreamPublisher

trait ServiceWithRuntimeFailureEvents extends ServiceCellSysevents {
  override def componentId: String = "ServiceWithRuntimeFailure"
}

object ServiceWithRuntimeFailureEvents extends ServiceWithRuntimeFailureEvents

object ServiceWithRuntimeFailureActor {
  var recoveryEnabled = false
  var failureCounter = 0
}

class ServiceWithRuntimeFailureActor(id: String) extends ServiceCell(id) with ServiceWithRuntimeFailureEvents {
  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    self ! "tick"
  }

  onMessage {
    case "tick" =>
      if (ServiceWithInitialisationFailureActor.recoveryEnabled) ServiceWithInitialisationFailureActor.failureCounter += 1

      if (!ServiceWithInitialisationFailureActor.recoveryEnabled || ServiceWithInitialisationFailureActor.failureCounter < 5)
        throw new RuntimeException("simulated failure on first tick")

  }
}

