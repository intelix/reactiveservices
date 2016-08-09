package rs.samples

import au.com.intelix.evt.{EvtSource, InfoE}
import au.com.intelix.rs.core.{Subject, TopicKey}
import au.com.intelix.rs.core.services.StatelessServiceActor
import rs.samples.SimpleService.Evt

object SimpleService {
  object Evt {
    case object Hello extends InfoE
  }
}

class SimpleService extends StatelessServiceActor {
  override val evtSource: EvtSource = "SimpleService"

  raise(Evt.Hello, 'from -> self, 'on -> nodeId)

  onSignal {
    case (Subject(_, TopicKey("hello"), _), body: String) => SignalOk(body + " - hey")
  }

}
