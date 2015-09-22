package rs.core

import org.scalatest.FlatSpec
import rs.core.registry.ServiceRegistrySysevents
import rs.testing.components.TestServiceActor
import rs.testing.{IsolatedActorSystems, UnmanagedNodeTestContext}


class BaseNodeContextTest extends FlatSpec with UnmanagedNodeTestContext with IsolatedActorSystems {

  "Reactive Services Test framework" should "start with a single node" in new WithNode1 {
    onNode1ExpectSomeEventsWithTimeout(15000, 1, ServiceRegistrySysevents.PreStart)
  }
  it should "start with more than one node" in new WithNode1 with WithNode2 {
    onNode1ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode2ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
  }
  it should "start with three nodes" in new WithNode1 with WithNode2 with WithNode3 {
    onNode1ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode2ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode3ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
  }
  it should "start with four nodes" in new WithNode1 with WithNode2 with WithNode3 with WithNode4 {
    onNode1ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode2ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode3ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode4ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
  }
  it should "start with four nodes, consistently" in new WithNode1 with WithNode2 with WithNode3 with WithNode4 {
    onNode1ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode2ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode3ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode4ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
  }

  trait With4NodesAndTestOn1 extends With4Nodes {
    override def node1Services = super.node1Services ++ Map("test-service" -> classOf[TestServiceActor])
  }

  it should "start service on node as requested" in new With4NodesAndTestOn1 {
    onNode1ExpectSomeEventsWithTimeout(10000, 1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
  }

  it should "start service on node as requested, consistently" in new With4NodesAndTestOn1 {
    onNode1ExpectSomeEventsWithTimeout(10000, 1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
  }

  it should "start more than one service on the same node as requested" in new With4NodesAndTestOn1 {
    override def node1Services = super.node1Services ++ Map("test-service2" -> classOf[TestServiceActor])

    onNode1ExpectSomeEventsWithTimeout(10000, 1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode1ExpectSomeEventsWithTimeout(10000, 1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service2")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service2")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service2")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service2")
  }

  it should "start more than one service on the different nodes as requested" in new With4NodesAndTestOn1 {
    override def node2Services = super.node2Services ++ Map("test-service2" -> classOf[TestServiceActor])

    onNode1ExpectSomeEventsWithTimeout(10000, 1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service")
    onNode1ExpectSomeEventsWithTimeout(10000, 1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service2")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service2")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service2")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service2")
  }


}
