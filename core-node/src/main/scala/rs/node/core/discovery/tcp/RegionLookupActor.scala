package rs.node.core.discovery.tcp

import rs.core.actors.StatefulActor
import rs.core.evt.EvtSource

private case class Data()

class RegionLookupActor(regionId: String, contacts: List[Endpoint]) extends StatefulActor[Data] {
  override val evtSource: EvtSource = _
}
