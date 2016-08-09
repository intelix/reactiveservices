package au.com.intelix.rs.node.testkit

import akka.actor.ActorRef
import akka.remote.MgmtService
import akka.remote.MgmtService.{Block, Unblock}
import au.com.intelix.config.RootConfig
import au.com.intelix.essentials.uuid.UUIDTools
import au.com.intelix.evt.testkit.EvtAssertions
import au.com.intelix.rs.core.actors.CommonActorEvt
import au.com.intelix.rs.core.testkit._
import au.com.intelix.rs.node.core.discovery.ClusterWatcherActor
import au.com.intelix.rs.node.core.discovery.regionbased.TrafficBlocking.{BlockCommunicationWith, UnblockCommunicationWith}
import au.com.intelix.rs.node.core.{ClusterNodeActor, ServiceClusterGuardianActor}
import org.scalatest.Suite

import scala.concurrent.duration.DurationLong

/**
  * Created by maks on 25/07/2016.
  */
trait ManagedNodeTestContext extends MultiActorSystemTestContext with EvtAssertions with AbstractNodeTestContext {
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
       |  port = ${akkaPortFor(idx)}
       |  cluster {
       |    discovery {
       |      timeout = ${if (idx == 1) "1" else "5"} seconds
       |
       |      region-based-http {
       |        query-interval-fast = 2s
       |        query-interval-paced = 2s
       |        query-interval-continuous = 2s
       |        exposure.enabled = on
       |        exposure.port = ${discoveryPortFor(idx)}
       |        regions-required = ["Region1", "Region2"]
       |        region.Region1.contacts = [
       |          "localhost:${discoveryPortFor(1)}"
       |        ]
       |        region.Region2.contacts = [
       |          "localhost:${discoveryPortFor(2)}"
       |        ]
     |        }
     |
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
       |include "akka-test"
       |     """.stripMargin


  protected def discoveryPortFor(idx: Int) = akkaPortFor(idx) + 10000

  override protected def startWithConfig(idx: Int, configs: ConfigReference*): ActorRef =
    withSystem(instanceId(idx)) { implicit sys =>
      val config = buildConfig(configs: _*)
      sys.start(ServiceClusterGuardianActor.props(RootConfig(config)), UUIDTools.generateUUID)
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

    def onNode1BlockClusterExposure() = serviceOnNode1(ClusterNodeActor.DiscoveryMgrId) ! BlockCommunicationWith("localhost", discoveryPortFor(1))

    def onNode1UnblockClusterExposure() = serviceOnNode1(ClusterNodeActor.DiscoveryMgrId) ! UnblockCommunicationWith("localhost", discoveryPortFor(1))

    def onNode1BlockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode1("mgmt") ! Block(Seq(akkaPortFor(i)))
    }

    def onNode1UnblockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode1("mgmt") ! Unblock(Seq(akkaPortFor(i)))
    }
  }

  trait WithGremlinOnNode2 extends WithNode2 {
    def onNode2BlockClusterExposure() = serviceOnNode2(ClusterNodeActor.DiscoveryMgrId) ! BlockCommunicationWith("localhost", discoveryPortFor(2))

    def onNode2UnblockClusterExposure() = serviceOnNode2(ClusterNodeActor.DiscoveryMgrId) ! UnblockCommunicationWith("localhost", discoveryPortFor(2))

    def onNode2BlockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode2("mgmt") ! Block(Seq(akkaPortFor(i)))
    }

    def onNode2UnblockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode2("mgmt") ! Unblock(Seq(akkaPortFor(i)))
    }

  }

  trait WithGremlinOnNode3 extends WithNode3 {

    def onNode3BlockClusterExposure() = serviceOnNode3(ClusterNodeActor.DiscoveryMgrId) ! BlockCommunicationWith("localhost", discoveryPortFor(3))

    def onNode3UnblockClusterExposure() = serviceOnNode3(ClusterNodeActor.DiscoveryMgrId) ! UnblockCommunicationWith("localhost", discoveryPortFor(3))

    def onNode3BlockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode3("mgmt") ! Block(Seq(akkaPortFor(i)))
    }

    def onNode3UnblockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode3("mgmt") ! Unblock(Seq(akkaPortFor(i)))
    }

  }

  trait WithGremlinOnNode4 extends WithNode4 {

    def onNode4BlockClusterExposure() = serviceOnNode4(ClusterNodeActor.DiscoveryMgrId) ! BlockCommunicationWith("localhost", discoveryPortFor(4))

    def onNode4UnblockClusterExposure() = serviceOnNode4(ClusterNodeActor.DiscoveryMgrId) ! UnblockCommunicationWith("localhost", discoveryPortFor(4))

    def onNode4BlockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode4("mgmt") ! Block(Seq(akkaPortFor(i)))
    }

    def onNode4UnblockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode4("mgmt") ! Unblock(Seq(akkaPortFor(i)))
    }
  }

  trait WithGremlinOnNode5 extends WithNode5 {

    def onNode5BlockClusterExposure() = serviceOnNode5(ClusterNodeActor.DiscoveryMgrId) ! BlockCommunicationWith("localhost", discoveryPortFor(5))

    def onNode5UnblockClusterExposure() = serviceOnNode5(ClusterNodeActor.DiscoveryMgrId) ! UnblockCommunicationWith("localhost", discoveryPortFor(5))

    def atNode5BlockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode5("mgmt") ! Block(Seq(akkaPortFor(i)))
    }

    def atNode5UnblockNode(idx: Int*) = idx.foreach { i =>
      serviceOnNode5("mgmt") ! Unblock(Seq(akkaPortFor(i)))
    }
  }

  trait With2Nodes extends WithNode1 with WithNode2 {
    eventTimeout = EventWaitTimeout(20.seconds)

    def expectFullyBuilt() {
      on node1 expectSome of CommonActorEvt.Evt.StateTransition  + ('to -> "Joined") + classOf[ClusterNodeActor]
      on node2 expectSome of CommonActorEvt.Evt.StateTransition  + ('to -> "Joined") + classOf[ClusterNodeActor]
      on node2 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node1Address)
      on node2 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node2Address)
      on node1 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node2Address)
    }
  }

  trait With3Nodes extends With2Nodes with WithNode3 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      on node3 expectSome of CommonActorEvt.Evt.StateTransition + classOf[ClusterNodeActor] + ('to -> "Joined")
      on node3 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node1Address)
      on node3 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node2Address)
      on node3 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node3Address)
      on node1 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node3Address)
    }
  }

  trait With4Nodes extends With3Nodes with WithNode4 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      on node4 expectSome of CommonActorEvt.Evt.StateTransition + classOf[ClusterNodeActor] + ('to -> "Joined")
      on node4 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node1Address)
      on node4 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node2Address)
      on node4 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node3Address)
      on node4 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node4Address)
      on node1 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node4Address)
      on node2 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node4Address)
      on node3 expectOne of ClusterWatcherActor.Evt.NodeUp + ('addr -> node4Address)
    }
  }

  trait WithGremlinOn4Nodes extends With4Nodes with WithGremlinOnNode1 with WithGremlinOnNode2 with WithGremlinOnNode3 with WithGremlinOnNode4

  trait With5Nodes extends With4Nodes with WithNode5 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      on node5 expectSome of CommonActorEvt.Evt.StateTransition + classOf[ClusterNodeActor] + ('to -> "Joined")
    }
  }

  trait WithGremlinOn5Nodes extends WithGremlinOn4Nodes with WithGremlinOnNode5

}
