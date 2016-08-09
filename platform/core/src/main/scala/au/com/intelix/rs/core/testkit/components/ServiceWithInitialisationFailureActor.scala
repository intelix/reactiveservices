package au.com.intelix.rs.core.testkit.components

import au.com.intelix.evt.EvtSource
import au.com.intelix.rs.core.services.StatelessServiceActor


object ServiceWithInitialisationFailureActor {
  var recoveryEnabled = false
  var failureCounter = 0

}

class ServiceWithInitialisationFailureActor extends StatelessServiceActor {

  import ServiceWithInitialisationFailureActor._

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    if (ServiceWithInitialisationFailureActor.recoveryEnabled) ServiceWithInitialisationFailureActor.failureCounter += 1
    if (!ServiceWithInitialisationFailureActor.recoveryEnabled || ServiceWithInitialisationFailureActor.failureCounter < 5)
      throw new RuntimeException("simulated failure on preStart")

  }

}

