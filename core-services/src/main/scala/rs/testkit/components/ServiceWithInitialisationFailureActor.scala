package rs.testkit.components

import rs.core.services.{StatelessServiceActor, ServiceEvt}

trait ServiceWithInitialisationFailureEvt extends ServiceEvt {
  override def componentId: String = "Test.FaultyService"
}

object ServiceWithInitialisationFailureEvt extends ServiceWithInitialisationFailureEvt

object ServiceWithInitialisationFailureActor {
  var recoveryEnabled = false
  var failureCounter = 0
}

class ServiceWithInitialisationFailureActor(id: String) extends StatelessServiceActor(id) with ServiceWithInitialisationFailureEvt {
  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    if (ServiceWithInitialisationFailureActor.recoveryEnabled) ServiceWithInitialisationFailureActor.failureCounter += 1

    if (!ServiceWithInitialisationFailureActor.recoveryEnabled || ServiceWithInitialisationFailureActor.failureCounter < 5)
      throw new RuntimeException("simulated failure on preStart")

  }
}

