package rs.samples

import rs.core.actors.StatelessActor
import rs.core.evt.{EvtSource, InfoE}
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
