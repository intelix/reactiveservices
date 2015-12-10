package rs.testing.components

import rs.core.services.{StatelessServiceActor, ServiceEvt}

trait ServiceWithRuntimeFailureEvt extends ServiceEvt {
  override def componentId: String = "Test.ServiceWithRuntimeFailure"
}

object ServiceWithRuntimeFailureEvt extends ServiceWithRuntimeFailureEvt

object ServiceWithRuntimeFailureActor {
  var recoveryEnabled = false
  var failureCounter = 0
}

class ServiceWithRuntimeFailureActor(id: String) extends StatelessServiceActor(id) with ServiceWithRuntimeFailureEvt {
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

