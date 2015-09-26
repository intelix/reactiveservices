package rs.testing

import org.scalatest.FlatSpec
import rs.core.registry.ServiceRegistrySysevents
import rs.node.core.ServiceNodeActor.Evt
import rs.node.core.discovery.UdpClusterManagerActor
import rs.node.core.{ServiceClusterBootstrapSysevents, ServiceClusterGuardianSysevents, ServiceNodeActor}
import rs.testing.components._

class ManagedNodeTest extends FlatSpec with ManagedNodeTestContext with IsolatedActorSystems {


  "Cluster guardian" should "start a node" in new WithNode1 {
    onNode1ExpectExactlyOneEvent(ServiceClusterBootstrapSysevents.StartingCluster)
    onNode1ExpectOneOrMoreEvents(ServiceNodeActor.Evt.StateChange)
  }


  it should "terminate in case of fatal error during initial bootstrap (eg bootstrap failure)" in new With2Nodes {
    onNode1ExpectExactlyOneEvent(ServiceClusterGuardianSysevents.PostStop)
    onNode1ExpectNoEvents(ServiceClusterBootstrapSysevents.PostRestart)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=4
          |node.cluster.system-id = []
          |node.cluster.discovery.timeout=0 seconds
        """.stripMargin)
  }

  it should "terminate (after configured number of attempts) in case of fatal error during node bootstrap (eg node failure)" in new With2Nodes {
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceClusterGuardianSysevents.PostStop)
    onNode1ExpectExactlyNEvents(4, ServiceClusterBootstrapSysevents.PostRestart)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=4
          |node.cluster.service-max-retries ='intentionally invalid config'
          |node.cluster.discovery.timeout=0 seconds
        """.stripMargin)
  }

  it should "terminate (after configured number of attempts) in case of fatal error during service bootstrap (eg failure in preStart)" in new With2Nodes {
    ServiceWithInitialisationFailureActor.recoveryEnabled = false
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceClusterGuardianSysevents.PostStop)
    onNode1ExpectExactlyNEvents(1, ServiceClusterBootstrapSysevents.PostRestart)
    onNode1ExpectSomeEventsWithTimeout(15000, 3 + 1 + 3 + 1, ServiceWithInitialisationFailureEvents.PreStart)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=1
          |node.cluster.service-max-retries=3
          |node.cluster.discovery.timeout=0 seconds
          |        """.stripMargin)

    override def node1Services = super.node1Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }

  it should "terminate (after configured number of attempts) in case of fatal error during service bootstrap (eg service runtime failure)" in new With2Nodes {
    ServiceWithRuntimeFailureActor.recoveryEnabled = false
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceClusterGuardianSysevents.PostStop)
    onNode1ExpectExactlyNEvents(1, ServiceClusterBootstrapSysevents.PostRestart)
    onNode1ExpectExactlyNEvents(3 + 1 + 3 + 1, ServiceWithRuntimeFailureEvents.PreStart)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=1
          |node.cluster.service-max-retries=3
          |node.cluster.discovery.timeout=0 seconds
          |        """.stripMargin)

    override def node1Services = super.node1Services ++ Map("test" -> classOf[ServiceWithRuntimeFailureActor])
  }

  it should "not terminate if service recover after several failures, if max not exceeded" in new With2Nodes {
    ServiceWithInitialisationFailureActor.recoveryEnabled = true
    ServiceWithInitialisationFailureActor.failureCounter = 0
    onNode1ExpectSomeEventsWithTimeout(15000, 5, ServiceWithInitialisationFailureEvents.PreStart)
    onNode1ExpectNoEvents(ServiceClusterGuardianSysevents.PostStop)
    onNode1ExpectNoEvents(ServiceClusterBootstrapSysevents.PostRestart)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=1
          |node.cluster.service-max-retries=6
          |node.cluster.discovery.timeout=0 seconds
          |        """.stripMargin)

    override def node1Services = super.node1Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }

  "Service node" should "terminate all services when restarted (due to other service initialisation failure)" in new With2Nodes {
    ServiceWithInitialisationFailureActor.recoveryEnabled = false
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceClusterGuardianSysevents.PostStop)
    onNode1ExpectExactlyNEvents(2, ServiceClusterBootstrapSysevents.PostRestart)

    onNode1ExpectExactlyNEvents(3, ServiceRegistrySysevents.PostStop)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=2
          |node.cluster.service-max-retries=2
          |node.cluster.discovery.timeout=0 seconds
          |        """.stripMargin)

    override def node1Services = super.node1Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }

  it should "not terminate any other services when restarting a service" in new With2Nodes {
    ServiceWithInitialisationFailureActor.recoveryEnabled = true
    ServiceWithInitialisationFailureActor.failureCounter = 0
    onNode1ExpectSomeEventsWithTimeout(15000, 5, ServiceWithInitialisationFailureEvents.PreStart)
    onNode1ExpectNoEvents(ServiceClusterGuardianSysevents.PostStop)
    onNode1ExpectNoEvents(ServiceClusterBootstrapSysevents.PostRestart)

    onNode1ExpectNoEvents(ServiceRegistrySysevents.PostStop)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=2
          |node.cluster.service-max-retries=10
          |        """.stripMargin)

    override def node1Services = super.node1Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }



  it should "join self when no other active cluster discovered" in new WithNode1 {

    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeActor.Evt.JoiningCluster, 'seeds -> node1Address.r)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+ ConfigFromContents("node.cluster.discovery.timeout=2s")
  }
  it should "not join self if not core node" in new WithNode3 {

    duringPeriodInMillis(5000) {
      onNode3ExpectNoEvents(ServiceNodeActor.Evt.JoiningCluster)
    }
  }

  it should "timeout from discovery if enabled and no core node to join" in new WithNode3 {

    duringPeriodInMillis(5000) {
      onNode3ExpectNoEvents(ServiceNodeActor.Evt.JoiningCluster)
    }
    onNode3ExpectExactlyOneEvent(ServiceNodeActor.Evt.StateChange, 'to -> "ClusterFormationPending")

    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+
      ConfigFromContents("node.cluster.discovery.timeout=2 seconds")
  }

  it should "result in joining some node after timeout discovery if not all cores are present" in new WithNode3 with WithNode2 {
    onNode3ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.StateChange, 'to -> "ClusterFormationPending")
    onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.StateChange, 'to -> "ClusterFormationPending")

    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.JoiningCluster, 'seeds -> node2Address.r)
    onNode3ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.JoiningCluster, 'seeds -> s"Set($node2Address)")

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+
      ConfigFromContents("node.cluster.discovery.timeout=2 seconds")
  }


  trait With4NodesAndTestOn1 extends With4Nodes {
    onNode1ExpectSomeEventsWithTimeout(20000, 4, TestServiceActor.Evt.NodeAvailable)

    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor])


  }

  it should "discover other nodes" in new With4NodesAndTestOn1 {
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StateChange, 'to -> "Joined", 'from -> "Joining")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StateChange, 'to -> "Joined", 'from -> "Joining")
    onNode3ExpectExactlyOneEvent(ServiceNodeActor.Evt.StateChange, 'to -> "Joined", 'from -> "Joining")
    onNode4ExpectExactlyOneEvent(ServiceNodeActor.Evt.StateChange, 'to -> "Joined", 'from -> "Joining")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.JoiningCluster, 'seeds -> s"Set($node1Address)")
    onNode3ExpectExactlyOneEvent(ServiceNodeActor.Evt.JoiningCluster, 'seeds -> s"Set($node1Address)")
    onNode4ExpectExactlyOneEvent(ServiceNodeActor.Evt.JoiningCluster, 'seeds -> s"Set($node1Address)")
  }

  it should "join formed cluster if discovered" in new WithNode2 {
    onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")

    new WithNode1 {
      onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.JoiningCluster, 'seeds -> s"Set($node2Address)")
    }


    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+
      ConfigFromContents("node.cluster.discovery.timeout=1 seconds")
  }



  it should "join formed cluster even if some cores are missing" in new WithNode2 {
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StateChange, 'to -> "Joined")
    new WithNode3 {
      onNode3ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")
    }
//    onNode2ExpectNoEvents(ServiceNodeActor.Evt.StateChange, 'to -> "Joined")
    printRaisedEvents()

    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+
      ConfigFromContents("node.cluster.discovery.timeout=1 seconds")
  }

  it should "merge with other cluster when discovered" in new WithGremlin with WithGremlinOnNode2 {
    // creating cluster island on node 2
    onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")

    // blocking traffic between node 1 and 2 so node 1 doesn't merge on startup

    atNode2BlockNode(1)

    // creating island cluster on node 1
    new WithGremlin with WithGremlinOnNode1 {
      onNode1ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")

      // unblocking
      atNode2UnblockNode(1)

      onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.ClusterMergeTrigger)
      onNode1ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)

      onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.JoiningCluster, 'seeds -> s"Set($node1Address)")
    }

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff

  }

  it should "always merge in the same direction - from less priority address to higher priority address" in new WithGremlin with WithGremlinOnNode1 {
    // creating cluster island on node 1
    onNode1ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")

    // blocking traffic between node 1 and 2 so node 1 doesn't merge on startup
    atNode1BlockNode(2)


    // creating island cluster on node 2
    new WithGremlin with WithGremlinOnNode2 {
      onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")

      // unblocking
      atNode1UnblockNode(2)


      onNode2ExpectSomeEventsWithTimeout(15000, ServiceNodeActor.Evt.ClusterMergeTrigger)
      onNode1ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)

      onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeActor.Evt.JoiningCluster, 'seeds -> s"Set($node1Address)")
    }

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff

  }

  it should "detect quarantine after network split (1,2/3,4) when running with auto-down enabled, and recover from it" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(3, 4)
    atNode2BlockNode(3, 4)

    atNode3BlockNode(1, 2)
    atNode4BlockNode(1, 2)

    onNode1ExpectSomeEventsWithTimeout(15000, 2, UdpClusterManagerActor.Evt.NodeRemoved)
    onNode2ExpectExactlyNEvents(2, UdpClusterManagerActor.Evt.NodeRemoved)
    onNode3ExpectSomeEventsWithTimeout(15000, 2, UdpClusterManagerActor.Evt.NodeRemoved)
    onNode4ExpectExactlyNEvents(2, UdpClusterManagerActor.Evt.NodeRemoved)

    atNode1UnblockNode(3, 4)
    atNode2UnblockNode(3, 4)
    atNode3UnblockNode(1, 2)
    atNode4UnblockNode(1, 2)

    onNode3ExpectSomeEventsWithTimeout(15000, Evt.ClusterMergeTrigger)
    onNode4ExpectSomeEventsWithTimeout(15000, Evt.ClusterMergeTrigger)

    onNode1ExpectNoEvents(Evt.ClusterMergeTrigger)


    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeUp, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node3Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node3Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node1Address)

    printRaisedEvents()

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOn
  }

  it should "detect quarantine after network split (1/2,3,4) when running with auto-down enabled, and recover from it" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(2, 3, 4)
    atNode2BlockNode(1)
    atNode3BlockNode(1)
    atNode4BlockNode(1)

    onNode1ExpectSomeEventsWithTimeout(15000, 3, UdpClusterManagerActor.Evt.NodeRemoved)
    onNode2ExpectSomeEventsWithTimeout(15000, 1, UdpClusterManagerActor.Evt.NodeRemoved)


    atNode1UnblockNode(2, 3, 4)
    atNode2UnblockNode(1)
    atNode3UnblockNode(1)
    atNode4UnblockNode(1)

    onNode2ExpectSomeEventsWithTimeout(15000, Evt.ClusterMergeTrigger)
    onNode3ExpectSomeEventsWithTimeout(15000, Evt.ClusterMergeTrigger)
    onNode4ExpectSomeEventsWithTimeout(15000, Evt.ClusterMergeTrigger)

    onNode1ExpectNoEvents(Evt.ClusterMergeTrigger)


    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeUp, 'addr -> node2Address)
    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeUp, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node1Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node3Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOn
  }

  it should "detect quarantine after network split (1,2,3/4) when running with auto-down enabled, and recover from it" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(4)
    atNode2BlockNode(4)
    atNode3BlockNode(4)
    atNode4BlockNode(1, 2, 3)

    onNode1ExpectSomeEventsWithTimeout(15000, 1, UdpClusterManagerActor.Evt.NodeRemoved)
    onNode2ExpectSomeEventsWithTimeout(15000, 1, UdpClusterManagerActor.Evt.NodeRemoved)


    atNode1UnblockNode(4)
    atNode2UnblockNode(4)
    atNode3UnblockNode(4)
    atNode4UnblockNode(1, 2, 3)

    onNode4ExpectSomeEventsWithTimeout(15000, Evt.ClusterMergeTrigger)


    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(Evt.ClusterMergeTrigger)
      onNode2ExpectNoEvents(Evt.ClusterMergeTrigger)
      onNode3ExpectNoEvents(Evt.ClusterMergeTrigger)
    }



    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
    onNode2ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
    onNode3ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node1Address)

    printRaisedEvents()

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOn
  }


  it should "not cause quarantine after network split (1,2/3,4) and should recover cleanly if running with auto-down off" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(3, 4)
    atNode2BlockNode(3, 4)

    atNode3BlockNode(1, 2)
    atNode4BlockNode(1, 2)

    duringPeriodInMillis(10000) {
      onNode1ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode2ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode3ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode4ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
    }

    atNode1UnblockNode(3, 4)
    atNode2UnblockNode(3, 4)
    atNode3UnblockNode(1, 2)
    atNode4UnblockNode(1, 2)

    duringPeriodInMillis(10000) {
      onNode1ExpectNoEvents(Evt.ClusterMergeTrigger)
      onNode2ExpectNoEvents(Evt.ClusterMergeTrigger)
      onNode3ExpectNoEvents(Evt.ClusterMergeTrigger)
      onNode4ExpectNoEvents(Evt.ClusterMergeTrigger)
    }


    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node4Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node3Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node2Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }


  it should "not cause quarantine after network split (1/2,3,4) and should recover cleanly if running with auto-down off" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(2, 3, 4)
    atNode2BlockNode(1)

    atNode3BlockNode(1)
    atNode4BlockNode(1)

    duringPeriodInMillis(10000) {
      onNode1ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode2ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode3ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode4ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
    }

    atNode1UnblockNode(2, 3, 4)
    atNode2UnblockNode(1)
    atNode3UnblockNode(1)
    atNode4UnblockNode(1)


    duringPeriodInMillis(10000) {
      onNode1ExpectNoEvents(Evt.ClusterMergeTrigger)
      onNode2ExpectNoEvents(Evt.ClusterMergeTrigger)
      onNode3ExpectNoEvents(Evt.ClusterMergeTrigger)
      onNode4ExpectNoEvents(Evt.ClusterMergeTrigger)
    }

    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node4Address)
    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node2Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }


  it should "start services before cluster formation if requested" in new WithNode3 {
    onNode3ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test")

    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+ ConfigFromContents("node.start-services-before-cluster=on")

    override def node3Services: Map[String, Class[_]] = super.node3Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }

  it should "not start services before cluster formation if requested" in new WithNode3 {
    duringPeriodInMillis(10000) {
      onNode3ExpectNoEvents(ServiceNodeActor.Evt.StartingService, 'service -> "test")
    }

    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+ ConfigFromContents("node.start-services-before-cluster=off")

    override def node3Services: Map[String, Class[_]] = super.node3Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }

  it should "start services after cluster formation if requested" in new WithNode2 {
    onNode2ExpectSomeEventsWithTimeout(15000, ServiceNodeActor.Evt.StartingService, 'service -> "test")

    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+ ConfigFromContents("node.start-services-before-cluster=off")

    override def node2Services: Map[String, Class[_]] = super.node2Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }


}
