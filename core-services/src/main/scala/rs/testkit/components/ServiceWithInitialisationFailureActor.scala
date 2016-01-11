package rs.testkit.components

import rs.core.evt.EvtSource
import rs.core.services.StatelessServiceActor


object ServiceWithInitialisationFailureActor {
  var recoveryEnabled = false
  var failureCounter = 0

  val EvtSourceId = "Test.FaultyService"
}

class ServiceWithInitialisationFailureActor(id: String) extends StatelessServiceActor(id) {

  import ServiceWithInitialisationFailureActor._

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    if (ServiceWithInitialisationFailureActor.recoveryEnabled) ServiceWithInitialisationFailureActor.failureCounter += 1

    if (!ServiceWithInitialisationFailureActor.recoveryEnabled || ServiceWithInitialisationFailureActor.failureCounter < 5)
      throw new RuntimeException("simulated failure on preStart")

  }

  override val evtSource: EvtSource = EvtSourceId
}

