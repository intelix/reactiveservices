package rs.examples.counter

import rs.node.core.ServiceNodeActor
import rs.testing.{ConfigFromFile, ConfigFromContents, ConfigReference, StandardMultiNodeSpec}

class CounterServiceTest extends StandardMultiNodeSpec {

  trait With2NodesAndServiceOn1 extends With4Nodes {
    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node1Services = super.node1Services ++ Map("counter" -> classOf[CounterService])

    on node1 expectOne of ServiceNodeActor.Evt.StartingService + ('service -> "counter")
    clearEvents()
  }

  "Counter service" should "start on node1" in new With2NodesAndServiceOn1 {
    on node1 expectOne of ServiceNodeActor.Evt.StartingService
  }

}
