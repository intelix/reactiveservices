package rs.testing

import akka.actor.PoisonPill
import org.scalatest.FlatSpec
import rs.core.registry.ServiceRegistrySysevents
import rs.core.{TestServiceActor, TestServiceActorEvents, TestServiceConsumer}
import rs.node.core.{ServiceNodeSysevents, ServiceClusterBootstrapSysevents}

class ManagedCoreServiceTest extends FlatSpec with ManagedNodeTestContext with IsolatedActorSystems {

  "Cluster guardian" should "start a node" in new WithNode1 {
    onNode1ExpectExactlyOneEvent(ServiceClusterBootstrapSysevents.StartingCluster)
    onNode1ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange)
  }

  trait With4NodesAndTestOn1 extends With4Nodes {
    onNode1ExpectSomeEventsWithTimeout(10000, 4, TestServiceActorEvents.NodeAvailable)
    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor])
  }

  "Test service on Node1" should "start and create remote endpoints" in new With4NodesAndTestOn1 {
    onNode1ExpectExactlyOneEvent(TestServiceActorEvents.RemoteEndpointRegistered, 'location -> node2Address)
    onNode1ExpectExactlyOneEvent(TestServiceActorEvents.RemoteEndpointRegistered, 'location -> node1Address)
    onNode1ExpectExactlyOneEvent(TestServiceActorEvents.RemoteEndpointRegistered, 'location -> node3Address)
    onNode1ExpectExactlyOneEvent(TestServiceActorEvents.RemoteEndpointRegistered, 'location -> node4Address)
    printRaisedEvents()
  }

  it should "register its remote endpoints with local registries on each node" in new With4NodesAndTestOn1 {
    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
  }

  it should "not conflict with another service with the same name started on some other node" in new With4NodesAndTestOn1 {
    override def node2Services = super.node2Services ++ Map("test" -> classOf[TestServiceActor])

    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test", 'locations -> 2)
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test", 'locations -> 2)
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test", 'locations -> 2)
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test", 'locations -> 2)

    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.PublishingNewServiceLocation, 'key -> "test")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.PublishingNewServiceLocation, 'key -> "test")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.PublishingNewServiceLocation, 'key -> "test")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.PublishingNewServiceLocation, 'key -> "test")

  }

  it should "remove remote endpoints and unregister location from local registries upon termination" in new With4NodesAndTestOn1 {

    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")

    sendToServiceOnNode1("test", PoisonPill)

    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceUnregistered, 'key -> "test", 'remainingLocations -> 0)
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceUnregistered, 'key -> "test", 'remainingLocations -> 0)
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceUnregistered, 'key -> "test", 'remainingLocations -> 0)
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceUnregistered, 'key -> "test", 'remainingLocations -> 0)
  }

  it should "accept subscription from consumer from the same node" in new With4NodesAndTestOn1 {
    override def node1Services = super.node1Services ++ Map("consumer" -> classOf[TestServiceConsumer])

    collectAndPrintEvents()
  }

}
