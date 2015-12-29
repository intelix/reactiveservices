/*
 * Copyright 2014-16 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rs.node.core

import org.scalatest.FlatSpec
import rs.core.registry.ServiceRegistrySysevents
import rs.node.core.discovery.UdpClusterManagerActorEvt
import rs.testing.components._
import rs.testing._

import scala.concurrent.duration._
import scala.language.postfixOps

class ManagedNodeTest extends StandardMultiNodeSpec {


  "Cluster guardian" should "start a node" in new WithNode1 {
    on node1 expectOne of ServiceClusterBootstrapActorEvt.StartingCluster
    on node1 expectSome of ClusterNodeActorEvt.StateChange
  }


  it should "terminate in case of fatal error during initial bootstrap (eg bootstrap failure)" in new With2Nodes {
    on node1 expectOne of ServiceClusterGuardianActorEvt.PostStop
    on node1 expectNone of ServiceClusterBootstrapActorEvt.PostRestart

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=4
          |node.cluster.system-id = []
          |node.cluster.discovery.timeout=0 seconds
        """.stripMargin)
  }

  it should "terminate (after configured number of attempts) in case of fatal error during node bootstrap (eg node failure)" in new With2Nodes {
    on node1 expectSome of ServiceClusterGuardianActorEvt.PostStop
    on node1 expect(4) of ServiceClusterBootstrapActorEvt.PostRestart

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
    on node1 expectSome of ServiceClusterGuardianActorEvt.PostStop
    on node1 expect(1) of ServiceClusterBootstrapActorEvt.PostRestart
    on node1 expect(3 + 1 + 3 + 1) of ServiceWithInitialisationFailureEvt.PreStart

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
    on node1 expectSome of ServiceClusterGuardianActorEvt.PostStop
    on node1 expect(1) of ServiceClusterBootstrapActorEvt.PostRestart
    on node1 expect(3 + 1 + 3 + 1) of ServiceWithRuntimeFailureEvt.PreStart

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
    on node1 expect(5) of ServiceWithInitialisationFailureEvt.PreStart
    on node1 expectNone of ServiceClusterGuardianActorEvt.PostStop
    on node1 expectNone of ServiceClusterBootstrapActorEvt.PostRestart

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
    on node1 expectSome of ServiceClusterGuardianActorEvt.PostStop
    on node1 expect(2) of ServiceClusterBootstrapActorEvt.PostRestart

    on node1 expect(3) of ServiceRegistrySysevents.PostStop

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
    on node1 expect(5) of ServiceWithInitialisationFailureEvt.PreStart
    on node1 expectNone of ServiceClusterGuardianActorEvt.PostStop
    on node1 expectNone of ServiceClusterBootstrapActorEvt.PostRestart

    on node1 expectNone of ServiceRegistrySysevents.PostStop

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+
      ConfigFromContents(
        """
          |node.cluster.max-retries=2
          |node.cluster.service-max-retries=10
          |        """.stripMargin)

    override def node1Services = super.node1Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }



  it should "join self when no other active cluster discovered" in new WithNode1 {

    on node1 expectSome of ClusterNodeActorEvt.JoiningCluster + ('seeds -> node1Address.r)

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+ ConfigFromContents("node.cluster.discovery.timeout=2s")
  }
  it should "not join self if not core node" in new WithNode3 {

    within(5 seconds) {
      on node3 expectNone of ClusterNodeActorEvt.JoiningCluster
    }
  }

  it should "timeout from discovery if enabled and no core node to join" in new WithNode3 {

    within(5 seconds) {
      on node3 expectNone of ClusterNodeActorEvt.JoiningCluster
    }
    on node3 expectOne of ClusterNodeActorEvt.StateChange + ('to -> "ClusterFormationPending")

    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+
      ConfigFromContents("node.cluster.discovery.timeout=2 seconds")
  }

  it should "result in joining some node after timeout discovery if not all cores are present" in new WithNode3 with WithNode2 {
    on node3 expectSome of ClusterNodeActorEvt.StateChange + ('to -> "ClusterFormationPending")
    on node2 expectSome of ClusterNodeActorEvt.StateChange + ('to -> "ClusterFormationPending")

    on node2 expectOne of ClusterNodeActorEvt.JoiningCluster + ('seeds -> node2Address.r)
    on node3 expectSome of ClusterNodeActorEvt.JoiningCluster + ('seeds -> s"Set($node2Address)")

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+
      ConfigFromContents("node.cluster.discovery.timeout=2 seconds")
  }


  trait With4NodesAndTestOn1 extends With4Nodes {
    on node1 expect(4) of TestServiceActorEvt.NodeAvailable

    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor])


  }

  it should "discover other nodes" in new With4NodesAndTestOn1 {
    on node1 expectOne of ClusterNodeActorEvt.StateChange +('to -> "Joined", 'from -> "Joining")
    on node2 expectOne of ClusterNodeActorEvt.StateChange +('to -> "Joined", 'from -> "Joining")
    on node3 expectOne of ClusterNodeActorEvt.StateChange +('to -> "Joined", 'from -> "Joining")
    on node4 expectOne of ClusterNodeActorEvt.StateChange +('to -> "Joined", 'from -> "Joining")
    on node2 expectOne of ClusterNodeActorEvt.JoiningCluster + ('seeds -> s"Set($node1Address)")
    on node3 expectOne of ClusterNodeActorEvt.JoiningCluster + ('seeds -> s"Set($node1Address)")
    on node4 expectOne of ClusterNodeActorEvt.JoiningCluster + ('seeds -> s"Set($node1Address)")
  }

  it should "join formed cluster if discovered" in new WithNode2 {
    on node2 expectSome of ClusterNodeActorEvt.StateChange + ('to -> "Joined")

    new WithNode1 {
      on node1 expectOne of ClusterNodeActorEvt.JoiningCluster + ('seeds -> s"Set($node2Address)")
    }


    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+
      ConfigFromContents("node.cluster.discovery.timeout=1 seconds")
  }



  it should "join formed cluster even if some cores are missing" in new WithNode2 {
    on node2 expectOne of ClusterNodeActorEvt.StateChange + ('to -> "Joined")
    new WithNode3 {
      on node3 expectSome of ClusterNodeActorEvt.StateChange + ('to -> "Joined")
    }

    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+
      ConfigFromContents("node.cluster.discovery.timeout=1 seconds")
  }

  it should "merge with other cluster when discovered" in new WithGremlin with WithGremlinOnNode2 {
    // creating cluster island on node 2
    on node2 expectSome of ClusterNodeActorEvt.StateChange + ('to -> "Joined")

    // blocking traffic between node 1 and 2 so node 1 doesn't merge on startup

    onNode2BlockNode(1)

    // creating island cluster on node 1
    new WithGremlin with WithGremlinOnNode1 {
      on node1 expectSome of ClusterNodeActorEvt.StateChange + ('to -> "Joined")

      // unblocking
      onNode2UnblockNode(1)

      on node2 expectOne of ClusterNodeActorEvt.ClusterMergeTrigger
      on node1 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger

      on node2 expectSome of ClusterNodeActorEvt.JoiningCluster + ('seeds -> s"Set($node1Address)")
    }

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff

  }

  it should "always merge in the same direction - from less priority address to higher priority address" in new WithGremlin with WithGremlinOnNode1 {
    // creating cluster island on node 1
    on node1 expectSome of ClusterNodeActorEvt.StateChange + ('to -> "Joined")

    // blocking traffic between node 1 and 2 so node 1 doesn't merge on startup
    onNode1BlockNode(2)


    // creating island cluster on node 2
    new WithGremlin with WithGremlinOnNode2 {
      on node2 expectSome of ClusterNodeActorEvt.StateChange + ('to -> "Joined")

      // unblocking
      onNode1UnblockNode(2)


      on node2 expectSome of ClusterNodeActorEvt.ClusterMergeTrigger
      on node1 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger

      on node2 expectSome of ClusterNodeActorEvt.JoiningCluster + ('seeds -> s"Set($node1Address)")
    }

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff

  }

  it should "detect quarantine after network split (1,2/3,4) when running with auto-down enabled, and recover from it" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    onNode1BlockNode(3, 4)
    onNode2BlockNode(3, 4)

    onNode3BlockNode(1, 2)
    onNode4BlockNode(1, 2)

    on node1 expect(2) of UdpClusterManagerActorEvt.NodeRemoved
    on node2 expect(2) of UdpClusterManagerActorEvt.NodeRemoved
    on node3 expect(2) of UdpClusterManagerActorEvt.NodeRemoved
    on node4 expect(2) of UdpClusterManagerActorEvt.NodeRemoved

    onNode1UnblockNode(3, 4)
    onNode2UnblockNode(3, 4)
    onNode3UnblockNode(1, 2)
    onNode4UnblockNode(1, 2)

    on node3 expectSome of ClusterNodeActorEvt.ClusterMergeTrigger
    on node4 expectSome of ClusterNodeActorEvt.ClusterMergeTrigger

    on node1 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger


    on node1 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node3Address)
    on node1 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node4Address)
    on node2 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node3Address)
    on node2 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node4Address)
    on node4 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node3Address)
    on node4 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOn
  }

  it should "detect quarantine after network split (1/2,3,4) when running with auto-down enabled, and recover from it" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    onNode1BlockNode(2, 3, 4)
    onNode2BlockNode(1)
    onNode3BlockNode(1)
    onNode4BlockNode(1)

    on node1 expect(3) of UdpClusterManagerActorEvt.NodeRemoved
    on node2 expect(1) of UdpClusterManagerActorEvt.NodeRemoved


    onNode1UnblockNode(2, 3, 4)
    onNode2UnblockNode(1)
    onNode3UnblockNode(1)
    onNode4UnblockNode(1)

    on node2 expectSome of ClusterNodeActorEvt.ClusterMergeTrigger
    on node3 expectSome of ClusterNodeActorEvt.ClusterMergeTrigger
    on node4 expectSome of ClusterNodeActorEvt.ClusterMergeTrigger

    on node1 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger


    on node1 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node2Address)
    on node1 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node3Address)
    on node1 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node4Address)
    on node2 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node1Address)
    on node2 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node4Address)
    on node4 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node3Address)
    on node4 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOn
  }

  it should "detect quarantine after network split (1,2,3/4) when running with auto-down enabled, and recover from it" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    onNode1BlockNode(4)
    onNode2BlockNode(4)
    onNode3BlockNode(4)
    onNode4BlockNode(1, 2, 3)

    on node1 expect(1) of UdpClusterManagerActorEvt.NodeRemoved
    on node2 expect(1) of UdpClusterManagerActorEvt.NodeRemoved


    onNode1UnblockNode(4)
    onNode2UnblockNode(4)
    onNode3UnblockNode(4)
    onNode4UnblockNode(1, 2, 3)

    on node4 expectSome of ClusterNodeActorEvt.ClusterMergeTrigger


    within(5 seconds) {
      on node1 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
      on node2 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
      on node3 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
    }



    on node1 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node4Address)
    on node2 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node4Address)
    on node3 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node4Address)
    on node4 expectSome of UdpClusterManagerActorEvt.NodeUp + ('addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOn
  }


  it should "not cause quarantine after network split (1,2/3,4) and should recover cleanly if running with auto-down off" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    onNode1BlockNode(3, 4)
    onNode2BlockNode(3, 4)

    onNode3BlockNode(1, 2)
    onNode4BlockNode(1, 2)

    within(10 seconds) {
      on node1 expectNone of UdpClusterManagerActorEvt.NodeRemoved
      on node2 expectNone of UdpClusterManagerActorEvt.NodeRemoved
      on node3 expectNone of UdpClusterManagerActorEvt.NodeRemoved
      on node4 expectNone of UdpClusterManagerActorEvt.NodeRemoved
    }

    onNode1UnblockNode(3, 4)
    onNode2UnblockNode(3, 4)
    onNode3UnblockNode(1, 2)
    onNode4UnblockNode(1, 2)

    within(10 seconds) {
      on node1 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
      on node2 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
      on node3 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
      on node4 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
    }


    on node1 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node3Address)
    on node1 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node4Address)
    on node2 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node3Address)
    on node2 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node4Address)
    on node4 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node2Address)
    on node4 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }


  it should "not cause quarantine after network split (1/2,3,4) and should recover cleanly if running with auto-down off" in new WithGremlin with WithGremlinOn4Nodes {

    expectFullyBuilt()
    clearEvents()

    onNode1BlockNode(2, 3, 4)
    onNode2BlockNode(1)

    onNode3BlockNode(1)
    onNode4BlockNode(1)

    within(10 seconds) {
      on node1 expectNone of UdpClusterManagerActorEvt.NodeRemoved
      on node2 expectNone of UdpClusterManagerActorEvt.NodeRemoved
      on node3 expectNone of UdpClusterManagerActorEvt.NodeRemoved
      on node4 expectNone of UdpClusterManagerActorEvt.NodeRemoved
    }

    onNode1UnblockNode(2, 3, 4)
    onNode2UnblockNode(1)
    onNode3UnblockNode(1)
    onNode4UnblockNode(1)


    within(10 seconds) {
      on node1 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
      on node2 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
      on node3 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
      on node4 expectNone of ClusterNodeActorEvt.ClusterMergeTrigger
    }

    on node1 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node3Address)
    on node1 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node4Address)
    on node1 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node2Address)
    on node2 expectSome of UdpClusterManagerActorEvt.NodeReachable + ('addr -> node1Address)

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }


  it should "start services before cluster formation if requested" in new WithNode3 {
    on node3 expectOne of ClusterNodeActorEvt.StartingService + ('service -> "test")

    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+ ConfigFromContents("node.start-services-before-cluster=on")

    override def node3Services: Map[String, Class[_]] = super.node3Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }

  it should "not start services before cluster formation if requested" in new WithNode3 {
    within(10 seconds) {
      on node3 expectNone of ClusterNodeActorEvt.StartingService + ('service -> "test")
    }

    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+ ConfigFromContents("node.start-services-before-cluster=off")

    override def node3Services: Map[String, Class[_]] = super.node3Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }

  it should "start services after cluster formation if requested" in new WithNode2 {
    on node2 expectSome of ClusterNodeActorEvt.StartingService + ('service -> "test")

    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+ ConfigFromContents("node.start-services-before-cluster=off")

    override def node2Services: Map[String, Class[_]] = super.node2Services ++ Map("test" -> classOf[ServiceWithInitialisationFailureActor])
  }


}
