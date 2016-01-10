package rs.testkit.components

import rs.core.evt.EvtSource
import rs.core.services.{ServiceEvt, StatelessServiceActor}

object ServiceWithRuntimeFailureActor {
  var recoveryEnabled = false
  var failureCounter = 0

  val EvtSourceId = "Test.ServiceWithRuntimeFailure"
}

class ServiceWithRuntimeFailureActor(id: String) extends StatelessServiceActor(id) {
  import ServiceWithRuntimeFailureActor._
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
  override val evtSource: EvtSource = EvtSourceId
}

