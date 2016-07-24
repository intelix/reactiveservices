package au.com.intelix.rs.core.testkit.components

import au.com.intelix.evt.EvtSource
import au.com.intelix.rs.core.services.StatelessServiceActor


object ServiceWithInitialisationFailureActor {
  var recoveryEnabled = false
  var failureCounter = 0

  val EvtSourceId = "Test.FaultyService"
}

class ServiceWithInitialisationFailureActor extends StatelessServiceActor {

  import ServiceWithInitialisationFailureActor._

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    if (ServiceWithInitialisationFailureActor.recoveryEnabled) ServiceWithInitialisationFailureActor.failureCounter += 1
    println(s"!>>> ServiceWithInitialisationFailureActor.recoveryEnabled = ${ServiceWithInitialisationFailureActor.recoveryEnabled}")
    println(s"!>>> ServiceWithInitialisationFailureActor.failureCounter = ${ServiceWithInitialisationFailureActor.failureCounter}")
    if (!ServiceWithInitialisationFailureActor.recoveryEnabled || ServiceWithInitialisationFailureActor.failureCounter < 5)
      throw new RuntimeException("simulated failure on preStart")

  }

  override val evtSource: EvtSource = EvtSourceId
}

