package rs.samples

import au.com.intelix.rs.core.actors.StatelessActor
import au.com.intelix.evt.{EvtSource, InfoE}
import rs.samples.SimpleService.Evt

object SimpleService {
  object Evt {
    case object Hello extends InfoE
  }
}

class SimpleService extends StatelessActor {
  override val evtSource: EvtSource = "SimpleService"

  raise(Evt.Hello, 'from -> self, 'on -> nodeId)

}
