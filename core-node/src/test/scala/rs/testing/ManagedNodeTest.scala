package rs.testing

import org.scalatest.FlatSpec
import rs.core._
import rs.core.registry.ServiceRegistrySysevents
import rs.node.core.{ServiceClusterBootstrapSysevents, ServiceClusterGuardianSysevents, ServiceNodeSysevents}

class ManagedNodeTest extends FlatSpec with ManagedNodeTestContext with IsolatedActorSystems {


  "Cluster guardian" should "start a node" in new WithNode1 {
    onNode1ExpectExactlyOneEvent(ServiceClusterBootstrapSysevents.StartingCluster)
    onNode1ExpectOneOrMoreEvents(ServiceNodeSysevents.StateChange)
  }


  it should "terminate in case of fatal error during initial bootstrap (eg bootstrap failure)" in new With2Nodes {
    onNode1ExpectExactlyOneEvent(ServiceClusterGuardianSysevents.PostStop)
    onNode1ExpectNoEvents(ServiceClusterBootstrapSysevents.PostRestart)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=4
          |node.cluster.system-id = []
          |node.cluster.discovery-timeout=0 seconds
        """.stripMargin)
  }

  it should "terminate (after configured number of attempts) in case of fatal error during node bootstrap (eg node failure)" in new With2Nodes {
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceClusterGuardianSysevents.PostStop)
    onNode1ExpectExactlyNEvents(4, ServiceClusterBootstrapSysevents.PostRestart)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=4
          |node.cluster.core-nodes='intentionally invalid config'
          |node.cluster.discovery-timeout=0 seconds
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
          |node.cluster.discovery-timeout=0 seconds
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
          |node.cluster.discovery-timeout=0 seconds
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
          |node.cluster.discovery-timeout=0 seconds
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
          |node.cluster.discovery-timeout=0 seconds
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
    onNode1ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.FormingClusterWithSelf)
  }
  it should "not join self if not core node" in new WithNode3 {

    duringPeriodInMillis(5000) {
      onNode3ExpectNoEvents(ServiceNodeSysevents.FormingClusterWithSelf)
    }
  }

  it should "timeout from discovery if enabled and no core node to join" in new WithNode3 {

    duringPeriodInMillis(5000) {
      onNode3ExpectNoEvents(ServiceNodeSysevents.FormingClusterWithSelf)
    }
    onNode3ExpectExactlyOneEvent(ServiceNodeSysevents.DiscoveryTimeout)

    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+
      ConfigFromContents("node.cluster.discovery-timeout=2 seconds")
  }

  it should "result in joining some node after timeout discovery if not all cores are present" in new WithNode3 with WithNode2 {
    onNode3ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.DiscoveryTimeout)
    onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.DiscoveryTimeout)

    onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.FormingClusterWithSelf)
    onNode3ExpectExactlyOneEvent(ServiceNodeSysevents.JoiningCluster, 'seeds -> s"List($node2Address)")

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+
      ConfigFromContents("node.cluster.discovery-timeout=2 seconds")
  }


  trait With4NodesAndTestOn1 extends With4Nodes {
    onNode1ExpectSomeEventsWithTimeout(10000, 4, TestServiceActorEvents.NodeAvailable)

    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor])


  }

  it should "discover other nodes" in new With4NodesAndTestOn1 {
    onNode1ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "AwaitingJoin", 'from -> "NodeSelection")
    onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "AwaitingJoin", 'from -> "NodeSelection")
    onNode3ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "AwaitingJoin", 'from -> "NodeSelection")
    onNode4ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "AwaitingJoin", 'from -> "NodeSelection")
    onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.JoiningCluster, 'seeds -> s"List($node1Address)")
    onNode3ExpectExactlyOneEvent(ServiceNodeSysevents.JoiningCluster, 'seeds -> s"List($node1Address)")
    onNode4ExpectExactlyOneEvent(ServiceNodeSysevents.JoiningCluster, 'seeds -> s"List($node1Address)")
  }

  it should "join formed cluster if discovered" in new WithNode2 {
    onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")

    new WithNode1 {
      onNode1ExpectExactlyOneEvent(ServiceNodeSysevents.JoiningCluster, 'seeds -> s"List($node2Address)")
    }


    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+
      ConfigFromContents("node.cluster.discovery-timeout=1 seconds")
  }

  it should "indicate a partially built state if core node is missing" in new WithNode2 {
    onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")

    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+
      ConfigFromContents("node.cluster.discovery-timeout=1 seconds")
  }

  it should "transition from partially built to fully built when missing node arrives" in new WithNode2 {
    onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")
    new WithNode1 {
      onNode1ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
    }
    onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")

    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+
      ConfigFromContents("node.cluster.discovery-timeout=1 seconds")
  }

  it should "transition from fully built to partially built when one node disappears" in new With4NodesAndTestOn1 {
    onNode1ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
    onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
    onNode3ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
    onNode4ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")

    clearEvents()
    stopNode2()

    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")
    onNode3ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")
    onNode4ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")

    printRaisedEvents()

  }

  it should "join formed cluster even if some cores are missing" in new WithNode2 {
    onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")
    new WithNode3 {
      onNode3ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")
    }
    onNode2ExpectNoEvents(ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")

    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+
      ConfigFromContents("node.cluster.discovery-timeout=1 seconds")
  }

  it should "merge with other cluster when discovered" in new WithGremlin with WithNode2 {
    // creating cluster island on node 2
    onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")

    // blocking traffic between node 1 and 2 so node 1 doesn't merge on startup
    atNode2BlockNode(1)

    // creating island cluster on node 1
    new WithGremlin with WithNode1 {
      onNode1ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")

      // unblocking
      atNode2UnblockNode(1)

      onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.ClusterMergeTrigger)
      onNode1ExpectNoEvents(ServiceNodeSysevents.ClusterMergeTrigger)

      onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.JoiningCluster, 'seeds -> s"List($node1Address)")
    }

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff

  }

  it should "always merge in the same direction - from less priority address to higher priority address" in new WithGremlin with WithNode1 {
    // creating cluster island on node 1
    onNode1ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")

    // blocking traffic between node 1 and 2 so node 1 doesn't merge on startup
    atNode1BlockNode(2)

    // creating island cluster on node 2
    new WithGremlin with WithNode2 {
      onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.StateChange, 'to -> "PartiallyBuilt")

      // unblocking
      atNode1UnblockNode(2)

      onNode2ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.ClusterMergeTrigger)
      onNode1ExpectNoEvents(ServiceNodeSysevents.ClusterMergeTrigger)

      onNode2ExpectSomeEventsWithTimeout(10000, ServiceNodeSysevents.JoiningCluster, 'seeds -> s"List($node1Address)")
    }

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff

  }

  it should "detect quarantine after network split (1,2/3,4) when running with auto-down enabled, and recover from it" in new WithGremlin with With4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(3, 4)
    atNode2BlockNode(3, 4)

    atNode3BlockNode(1, 2)
    atNode4BlockNode(1, 2)

    onNode1ExpectSomeEventsWithTimeout(15000, 2, ServiceNodeSysevents.NodeRemoved)
    onNode2ExpectExactlyNEvents(2, ServiceNodeSysevents.NodeRemoved)

    onNode3ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeQuarantined)
    onNode3ExpectExactlyOneEvent(ServiceNodeSysevents.QuarantineRecoveryTrigger)
    onNode4ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeQuarantined)
    onNode4ExpectExactlyOneEvent(ServiceNodeSysevents.QuarantineRecoveryTrigger)

    onNode1ExpectNoEvents(ServiceNodeSysevents.QuarantineRecoveryTrigger)
    onNode2ExpectNoEvents(ServiceNodeSysevents.QuarantineRecoveryTrigger)


    atNode1UnblockNode(3, 4)
    atNode2UnblockNode(3, 4)



    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeUp, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeUp, 'addr -> node4Address)
    onNode2ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeUp, 'addr -> node3Address)
    onNode2ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeUp, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeUp, 'addr -> node3Address)
    onNode4ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeUp, 'addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOn
  }

  it should "detect quarantine after network split (1/2,3,4) when running with auto-down enabled, and recover from it" in new WithGremlin with With4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(2, 3, 4)
    atNode2BlockNode(1)
    atNode3BlockNode(1)
    atNode4BlockNode(1)

    onNode1ExpectSomeEventsWithTimeout(15000, 3, ServiceNodeSysevents.NodeRemoved)
    onNode2ExpectSomeEventsWithTimeout(15000, 1, ServiceNodeSysevents.NodeRemoved)


    onNode2ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeQuarantined)
    onNode2ExpectExactlyOneEvent(ServiceNodeSysevents.QuarantineRecoveryTrigger)
    onNode3ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeQuarantined)
    onNode3ExpectExactlyOneEvent(ServiceNodeSysevents.QuarantineRecoveryTrigger)
    onNode4ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeQuarantined)
    onNode4ExpectExactlyOneEvent(ServiceNodeSysevents.QuarantineRecoveryTrigger)

    onNode1ExpectNoEvents(ServiceNodeSysevents.QuarantineRecoveryTrigger)

    atNode1UnblockNode(2, 3, 4)


    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeUp, 'addr -> node2Address)
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeUp, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeUp, 'addr -> node4Address)
    onNode2ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeUp, 'addr -> node1Address)
    onNode2ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeUp, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeUp, 'addr -> node3Address)
    onNode4ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeUp, 'addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOn
  }

  it should "detect quarantine after network split (1,2,3/4) when running with auto-down enabled, and recover from it" in new WithGremlin with With4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(4)
    atNode2BlockNode(4)
    atNode3BlockNode(4)
    atNode4BlockNode(1, 2, 3)

    onNode1ExpectSomeEventsWithTimeout(15000, 1, ServiceNodeSysevents.NodeRemoved)
    onNode2ExpectSomeEventsWithTimeout(15000, 1, ServiceNodeSysevents.NodeRemoved)


    onNode4ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeQuarantined)
    onNode4ExpectExactlyOneEvent(ServiceNodeSysevents.QuarantineRecoveryTrigger)

    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(ServiceNodeSysevents.QuarantineRecoveryTrigger)
      onNode2ExpectNoEvents(ServiceNodeSysevents.QuarantineRecoveryTrigger)
      onNode3ExpectNoEvents(ServiceNodeSysevents.QuarantineRecoveryTrigger)
    }


    atNode1UnblockNode(4)
    atNode2UnblockNode(4)

    atNode3UnblockNode(4)


    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeUp, 'addr -> node4Address)
    onNode2ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeUp, 'addr -> node4Address)
    onNode3ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeUp, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeUp, 'addr -> node1Address)

    printRaisedEvents()

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOn
  }


  it should "not cause quarantine after network split (1,2/3,4) and should recover cleanly if running with auto-down off" in new WithGremlin with With4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(3, 4)
    atNode2BlockNode(3, 4)

    atNode3BlockNode(1, 2)
    atNode4BlockNode(1, 2)

    duringPeriodInMillis(10000) {
      onNode1ExpectNoEvents(ServiceNodeSysevents.NodeRemoved)
      onNode2ExpectNoEvents(ServiceNodeSysevents.NodeRemoved)
      onNode3ExpectNoEvents(ServiceNodeSysevents.NodeRemoved)
      onNode4ExpectNoEvents(ServiceNodeSysevents.NodeRemoved)
      onNode3ExpectNoEvents(ServiceNodeSysevents.NodeQuarantined)
      onNode4ExpectNoEvents(ServiceNodeSysevents.NodeQuarantined)
    }

    atNode1UnblockNode(3, 4)
    atNode2UnblockNode(3, 4)
    atNode3UnblockNode(1, 2)
    atNode4UnblockNode(1, 2)



    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeReachable, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeReachable, 'addr -> node4Address)
    onNode2ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeReachable, 'addr -> node3Address)
    onNode2ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeReachable, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeReachable, 'addr -> node2Address)
    onNode4ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeReachable, 'addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }


  it should "not cause quarantine after network split (1/2,3,4) and should recover cleanly if running with auto-down off" in new WithGremlin with With4Nodes {

    expectFullyBuilt()
    clearEvents()

    atNode1BlockNode(2, 3, 4)
    atNode2BlockNode(1)

    atNode3BlockNode(1)
    atNode4BlockNode(1)

    duringPeriodInMillis(10000) {
      onNode1ExpectNoEvents(ServiceNodeSysevents.NodeRemoved)
      onNode2ExpectNoEvents(ServiceNodeSysevents.NodeRemoved)
      onNode3ExpectNoEvents(ServiceNodeSysevents.NodeRemoved)
      onNode4ExpectNoEvents(ServiceNodeSysevents.NodeRemoved)
      onNode3ExpectNoEvents(ServiceNodeSysevents.NodeQuarantined)
      onNode4ExpectNoEvents(ServiceNodeSysevents.NodeQuarantined)
    }

    atNode1UnblockNode(2, 3, 4)
    atNode2UnblockNode(1)
    atNode3UnblockNode(1)
    atNode4UnblockNode(1)



    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeReachable, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeReachable, 'addr -> node4Address)
    onNode1ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.NodeReachable, 'addr -> node2Address)
    onNode2ExpectOneOrMoreEvents(ServiceNodeSysevents.NodeReachable, 'addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }


  it should "start services before cluster formation if requested" in new WithNode3 {
    onNode3ExpectExactlyOneEvent(ServiceNodeSysevents.StartingService, 'service -> "test")

    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+ ConfigFromContents("node.start-services-before-cluster=on")

    override def node3Services: Map[String, Class[_]] = super.node3Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }

  it should "not start services before cluster formation if requested" in new WithNode3 {
    duringPeriodInMillis(10000) {
      onNode3ExpectNoEvents(ServiceNodeSysevents.StartingService, 'service -> "test")
    }

    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+ ConfigFromContents("node.start-services-before-cluster=off")

    override def node3Services: Map[String, Class[_]] = super.node3Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }

  it should "start services after cluster formation if requested" in new WithNode2 {
    onNode2ExpectSomeEventsWithTimeout(15000, ServiceNodeSysevents.StartingService, 'service -> "test")

    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+ ConfigFromContents("node.start-services-before-cluster=off")

    override def node2Services: Map[String, Class[_]] = super.node2Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }


}
