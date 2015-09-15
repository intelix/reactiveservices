package rs.core

import org.scalatest.FlatSpec
import rs.core.actors.{ClusterAwareness, ActorWithComposableBehavior, BaseActorSysevents}
import rs.core.registry.ServiceRegistrySysevents
import rs.core.services.ServiceCell
import rs.core.sysevents.support.EventAssertions
import rs.testing.{IsolatedActorSystems, ConfigReference, SharedActorSystem, UnmanagedNodeTestContext}

object BaseNodeContextTest {

  trait TestActorEvents extends BaseActorSysevents {

    val LeaderChanged = "LeaderChanged".info

    override def componentId: String = "Test"
  }

  object TestActorEvents extends TestActorEvents

  class TestActor(id: String) extends ServiceCell(id) with TestActorEvents with ClusterAwareness {

    override def onLeaderChanged(): Unit = LeaderChanged('self -> cluster.selfAddress.toString)
  }

}

class BaseNodeContextTest extends FlatSpec with UnmanagedNodeTestContext with IsolatedActorSystems {

  "Reactive Services Test framework" should "start with a single node" in new WithNode1 {
    onNode1ExpectSomeEventsWithTimeout(15000, 1, ServiceRegistrySysevents.PreStart)
  }
  it should "start with more than one node" in new WithNode1 with WithNode2 {
    onNode1ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
    onNode2ExpectExactlyNEvents(1, ServiceRegistrySysevents.PreStart)
  }


  "Cluster of three nodes" should "form when starting manually with blank nodes" in new WithNode1 with WithNode2 {

    expectSomeEventsWithTimeout(15000, 1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service", 'nodeid -> "Node2")
    expectExactlyNEvents(1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service", 'nodeid -> "Node1")

    override def node1Services = super.node1Services ++ Map("test-service" -> classOf[BaseNodeContextTest.TestActor])
  }

  it should "1" in new WithNode1 with WithNode2 {

    expectExactlyNEvents(1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service", 'nodeid -> "Node1")
    expectExactlyNEvents(1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service", 'nodeid -> "Node2")

    override def node1Services = super.node1Services ++ Map("test-service" -> classOf[BaseNodeContextTest.TestActor])
  }

  it should "2" in new WithNode1 with WithNode2 {

    expectExactlyNEvents(1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service", 'nodeid -> "Node1")
    expectExactlyNEvents(1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service", 'nodeid -> "Node2")

    override def node1Services = super.node1Services ++ Map("test-service" -> classOf[BaseNodeContextTest.TestActor])
  }

  it should "3" in new WithNode1 with WithNode2 {

    expectExactlyNEvents(1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service", 'nodeid -> "Node1")
    expectExactlyNEvents(1, ServiceRegistrySysevents.ServiceRegistered, 'key -> "test-service", 'nodeid -> "Node2")

    override def node1Services = super.node1Services ++ Map("test-service" -> classOf[BaseNodeContextTest.TestActor])
  }


}
