package rs.testing

import akka.actor.ActorRef
import akka.remote.MgmtService
import akka.remote.MgmtService.{Block, Unblock}
import org.scalatest.Suite
import rs.core.sysevents.support.EventAssertions
import rs.core.tools.UUIDTools
import rs.node.core.{ServiceClusterGuardianActor, ServiceNodeSysevents}

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
       |    discovery-timeout=5 seconds
       |    core-nodes = [
       |      "localhost:${portFor(1)}",
       |      "localhost:${portFor(2)}"
       |    ]
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
         |      threshold = 1
         |      min-std-deviation = 100 ms
         |      acceptable-heartbeat-pause = 3 s
         |      expected-response-after = 2 s
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

    def atNode1BlockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode1("mgmt", Block(portFor(i))) }

    def atNode1UnblockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode1("mgmt", Unblock(portFor(i))) }
  }

  trait WithGremlinOnNode2 extends WithNode2 {
    def atNode2BlockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode2("mgmt", Block(portFor(i))) }

    def atNode2UnblockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode2("mgmt", Unblock(portFor(i))) }

  }

  trait WithGremlinOnNode3 extends WithNode3 {
    def atNode3BlockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode3("mgmt", Block(portFor(i))) }

    def atNode3UnblockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode3("mgmt", Unblock(portFor(i))) }

  }

  trait WithGremlinOnNode4 extends WithNode4 {
    def atNode4BlockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode4("mgmt", Block(portFor(i))) }

    def atNode4UnblockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode4("mgmt", Unblock(portFor(i))) }
  }

  trait WithGremlinOnNode5 extends WithNode5 {
    def atNode5BlockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode5("mgmt", Block(portFor(i))) }

    def atNode5UnblockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode5("mgmt", Unblock(portFor(i))) }
  }

  trait With2Nodes extends WithNode1 with WithNode2 {
    def expectFullyBuilt() {
      onNode1ExpectSomeEventsWithTimeout(20000, ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
      onNode2ExpectSomeEventsWithTimeout(20000, ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
    }
  }

  trait With3Nodes extends With2Nodes with WithNode3 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      onNode3ExpectSomeEventsWithTimeout(20000, ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
    }
  }

  trait With4Nodes extends With3Nodes with WithNode4 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      onNode4ExpectSomeEventsWithTimeout(20000, ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
    }
  }
  trait WithGremlinOn4Nodes extends With4Nodes with WithGremlinOnNode1 with WithGremlinOnNode2 with WithGremlinOnNode3 with WithGremlinOnNode4

  trait With5Nodes extends With4Nodes with WithNode5 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      onNode5ExpectSomeEventsWithTimeout(20000, ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
    }
  }
  trait WithGremlinOn5Nodes extends WithGremlinOn4Nodes with WithGremlinOnNode5

}
