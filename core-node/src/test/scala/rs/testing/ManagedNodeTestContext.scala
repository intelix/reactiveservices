/*
 * Copyright 2014-15 Intelix Pty Ltd
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
package rs.testing

import akka.actor.ActorRef
import akka.remote.MgmtService
import akka.remote.MgmtService.{Block, Unblock}
import org.scalatest.Suite
import rs.core.sysevents.support.EventAssertions
import rs.core.tools.UUIDTools
import rs.node.core.discovery.UdpClusterManagerActor
import rs.node.core.discovery.UdpClusterManagerActor.{UnblockCommunicationWith, BlockCommunicationWith}
import rs.node.core.{ServiceClusterGuardianActor, ServiceNodeActor}

trait ManagedNodeTestContext extends MultiActorSystemTestContext with EventAssertions with AbstractNodeTestContext {
  _: Suite with ActorSystemManagement =>




  private def gremlinConfig =
    """
      |akka.remote.netty.tcp.applied-adapters=["gremlin"]
      |node.protocol="akka.gremlin.tcp"
      | """.stripMargin

  private def nodeConfig(idx: Int) =
    s"""
       |include "node-defaults"
       |
       |node {
       |  id = "${nodeId(idx)}"
       |  host = "localhost"
       |  port = ${portFor(idx)}
       |  cluster {
       |    discovery {
       |      pre-discovery-timeout = 2 seconds
       |      timeout = 5 seconds
       |      udp-endpoint.port = ${udpPortFor(idx)}
       |      udp-contacts = [
       |        "localhost:${udpPortFor(1)}",
       |        "localhost:${udpPortFor(2)}"
       |      ]
       |    }
       |  }
       |}
       |akka {
       |  cluster {
       |    gossip-interval = 300ms
       |    failure-detector {
       |      threshold = 1
       |      min-std-deviation = 100 ms
       |      acceptable-heartbeat-pause = 3 s
       |      expected-response-after = 2 s
       |    }
       |    auto-down-unreachable-after = 2s
       |  }
       |}
       |     """.stripMargin


  protected def udpPortFor(idx: Int) = portFor(idx) + 10000

  override protected def startWithConfig(idx: Int, configs: ConfigReference*): ActorRef =
    withSystem(instanceId(idx)) { implicit sys =>
      val config = buildConfig(configs: _*)
      sys.start(ServiceClusterGuardianActor.props(config), UUIDTools.generateShortUUID)
    }


  override protected def baseConfig(idx: Int): Seq[ConfigReference] = Seq(ConfigFromContents(nodeConfig(idx)))

  trait WithGremlin extends WithNode {

    override val protocol: String = "akka.gremlin.tcp"

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents(gremlinConfig)

    override def allNodesServices: Map[String, Class[_]] = super.allNodesServices + ("mgmt" -> classOf[MgmtService])


    private def sensitiveConfig = ConfigFromContents(
      s"""
         |akka {
         |  remote {
         |    retry-gate-closed-for = 200 ms
         |    transport-failure-detector {
         |      acceptable-heartbeat-pause = 3 s
         |    }
         |    watch-failure-detector {
         |      acceptable-heartbeat-pause = 3 s
         |      threshold = 1
         |    }
         |  }
         |  cluster {
         |    failure-detector {
         |      threshold = 6
         |      min-std-deviation = 100 ms
         |      acceptable-heartbeat-pause = 2 s
         |      expected-response-after = 1 s
         |    }
         |    auto-down-unreachable-after = 2s
         |  }
         |}
       """.stripMargin)

    def sensitiveConfigWithAutoDownOn = Seq(sensitiveConfig, ConfigFromContents( """akka.cluster.auto-down-unreachable-after = 2s"""))

    def sensitiveConfigWithAutoDownOff = Seq(sensitiveConfig, ConfigFromContents( """akka.cluster.auto-down-unreachable-after = off"""))

  }


  trait WithGremlinOnNode1 extends WithNode1 {
    private val nodeIdx = 1

    def atNode1BlockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode1("mgmt", Block(Seq(portFor(i))))
      sendToServiceOnNode1(ServiceNodeActor.DiscoveryMgrId, BlockCommunicationWith("localhost", udpPortFor(i)))
    }

    def atNode1UnblockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode1("mgmt", Unblock(Seq(portFor(i))))
      sendToServiceOnNode1(ServiceNodeActor.DiscoveryMgrId, UnblockCommunicationWith("localhost", udpPortFor(i)))
    }
  }

  trait WithGremlinOnNode2 extends WithNode2 {
    def atNode2BlockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode2("mgmt", Block(Seq(portFor(i))))
      sendToServiceOnNode2(ServiceNodeActor.DiscoveryMgrId, BlockCommunicationWith("localhost", udpPortFor(i)))
    }

    def atNode2UnblockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode2("mgmt", Unblock(Seq(portFor(i))))
      sendToServiceOnNode2(ServiceNodeActor.DiscoveryMgrId, UnblockCommunicationWith("localhost", udpPortFor(i)))
    }

  }

  trait WithGremlinOnNode3 extends WithNode3 {
    def atNode3BlockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode3("mgmt", Block(Seq(portFor(i))))
      sendToServiceOnNode3(ServiceNodeActor.DiscoveryMgrId, BlockCommunicationWith("localhost", udpPortFor(i)))
    }

    def atNode3UnblockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode3("mgmt", Unblock(Seq(portFor(i))))
      sendToServiceOnNode3(ServiceNodeActor.DiscoveryMgrId, UnblockCommunicationWith("localhost", udpPortFor(i)))
    }

  }

  trait WithGremlinOnNode4 extends WithNode4 {
    def atNode4BlockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode4("mgmt", Block(Seq(portFor(i))))
      sendToServiceOnNode4(ServiceNodeActor.DiscoveryMgrId, BlockCommunicationWith("localhost", udpPortFor(i)))
    }

    def atNode4UnblockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode4("mgmt", Unblock(Seq(portFor(i))))
      sendToServiceOnNode4(ServiceNodeActor.DiscoveryMgrId, UnblockCommunicationWith("localhost", udpPortFor(i)))
    }
  }

  trait WithGremlinOnNode5 extends WithNode5 {
    def atNode5BlockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode5("mgmt", Block(Seq(portFor(i))))
      sendToServiceOnNode5(ServiceNodeActor.DiscoveryMgrId, BlockCommunicationWith("localhost", udpPortFor(i)))
    }

    def atNode5UnblockNode(idx: Int*) = idx.foreach { i =>
      sendToServiceOnNode5("mgmt", Unblock(Seq(portFor(i))))
      sendToServiceOnNode5(ServiceNodeActor.DiscoveryMgrId, UnblockCommunicationWith("localhost", udpPortFor(i)))
    }
  }

  trait With2Nodes extends WithNode1 with WithNode2 {
    def expectFullyBuilt() {
      onNode1ExpectSomeEventsWithTimeout(20000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")
      onNode2ExpectSomeEventsWithTimeout(20000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")
      onNode2ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node1Address)
      onNode2ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node2Address)
      onNode1ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node2Address)
    }
  }

  trait With3Nodes extends With2Nodes with WithNode3 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      onNode3ExpectSomeEventsWithTimeout(20000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")
      onNode3ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node1Address)
      onNode3ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node2Address)
      onNode3ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node3Address)
      onNode1ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node3Address)
    }
  }

  trait With4Nodes extends With3Nodes with WithNode4 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      onNode4ExpectSomeEventsWithTimeout(20000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")
      onNode4ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node1Address)
      onNode4ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node2Address)
      onNode4ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node3Address)
      onNode4ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
      onNode1ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
      onNode2ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
      onNode3ExpectExactlyOneEvent(UdpClusterManagerActor.Evt.NodeUp, 'addr -> node4Address)
    }
  }

  trait WithGremlinOn4Nodes extends With4Nodes with WithGremlinOnNode1 with WithGremlinOnNode2 with WithGremlinOnNode3 with WithGremlinOnNode4

  trait With5Nodes extends With4Nodes with WithNode5 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      onNode5ExpectSomeEventsWithTimeout(20000, ServiceNodeActor.Evt.StateChange, 'to -> "Joined")
    }
  }

  trait WithGremlinOn5Nodes extends WithGremlinOn4Nodes with WithGremlinOnNode5

}
