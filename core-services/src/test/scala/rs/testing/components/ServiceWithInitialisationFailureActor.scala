package rs.testing.components

import rs.core.services.{StatelessServiceActor, ServiceEvt}

trait ServiceWithInitialisationFailureEvents extends ServiceEvt {
  override def componentId: String = "FaultyService"
}

object ServiceWithInitialisationFailureEvents extends ServiceWithInitialisationFailureEvents

object ServiceWithInitialisationFailureActor {
  var recoveryEnabled = false
  var failureCounter = 0
}

class ServiceWithInitialisationFailureActor(id: String) extends StatelessServiceActor(id) with ServiceWithInitialisationFailureEvents {
  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    if (ServiceWithInitialisationFailureActor.recoveryEnabled) ServiceWithInitialisationFailureActor.failureCounter += 1

    if (!ServiceWithInitialisationFailureActor.recoveryEnabled || ServiceWithInitialisationFailureActor.failureCounter < 5)
      throw new RuntimeException("simulated failure on preStart")

  }
}

