package rs.testing

import akka.remote.MgmtService
import akka.remote.MgmtService.{Unblock, Block}
import core.sysevents.FieldAndValue
import org.scalatest.Suite
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService
import rs.core.sysevents.Sysevent
import rs.core.sysevents.support.EventAssertions
import rs.core.tools.UUIDTools
import rs.node.core.{ServiceNodeSysevents, ServiceClusterGuardianActor}

trait ManagedNodeTestContext extends MultiActorSystemTestContext with EventAssertions {
  _: Suite with ActorSystemManagement =>

  private def instanceId(idx: Int) = s"node$idx"


  private val basePort = 3800

  private def portFor(idx: Int) = basePort + idx - 1

  private def nodeId(idx: Int) = "Node" + idx


  private def gremlinConfig =
    """
      |akka.remote.netty.tcp.applied-adapters=["gremlin"]
      |node.protocol="akka.gremlin.tcp"
      |""".stripMargin

  private def nodeConfig(idx: Int) =
    s"""
       |include "node-defaults"
       |
       |node {
       |  id = "${nodeId(idx)}"
       |  host = "localhost"
       |  port = ${portFor(idx)}
       |  cluster {
       |    gossip-interval = 300ms
       |    discovery-timeout=5 seconds
       |    core-nodes = [
       |      "localhost:${portFor(1)}",
       |      "localhost:${portFor(2)}"
       |    ]
       |  }
       |}
       |akka {
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
       |     """.stripMargin


  trait WithNode {

    val protocol = "akka.tcp"

    def allNodesConfigs: Seq[ConfigReference] = Seq.empty

    def allNodesServices: Map[String, Class[_]] = Map.empty


    def startNode(idx: Int, configs: ConfigReference*) =
      withSystem(instanceId(idx)) { implicit sys =>
        val namespace = UUIDTools.generateShortUUID
        val config = buildConfig(Seq(ConfigFromContents(nodeConfig(idx))) ++ Seq(serviceClassesToConfig(allNodesServices)) ++ allNodesConfigs ++ configs: _*)
        sys.start(ServiceClusterGuardianActor.props(config), namespace)
      }

    def restartNode(idx: Int, configs: ConfigReference*) = {
      destroySystem(instanceId(idx))
      startNode(idx, configs: _*)
    }

    def stopNode(idx: Int) = {
      destroySystem(instanceId(idx))
    }

    protected def serviceClassesToConfig(list: Map[String, Class[_]]) =
      ConfigFromContents(list.map {
        case (k, v) => s"""node.services.$k="${v.getName}""""
      } mkString "\n")
  }

  trait WithGremlin extends WithNode{

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
    def sensitiveConfigWithAutoDownOn = Seq(sensitiveConfig, ConfigFromContents("""akka.cluster.auto-down-unreachable-after = 2s"""))
    def sensitiveConfigWithAutoDownOff = Seq(sensitiveConfig, ConfigFromContents("""akka.cluster.auto-down-unreachable-after = off"""))


  }


  trait WithGremlinOps extends With4Nodes {

    override val protocol: String = "akka.gremlin.tcp"

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents(gremlinConfig)

    override def allNodesServices: Map[String, Class[_]] = super.allNodesServices + ("mgmt" -> classOf[MgmtService])


    private def sensitiveConfig = ConfigFromContents(
      s"""
         |akka {
         |  remote {
         |    retry-gate-closed-for = 1000 ms
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
    def sensitiveConfigWithAutoDownOn = Seq(sensitiveConfig, ConfigFromContents("""akka.cluster.auto-down-unreachable-after = 2s"""))
    def sensitiveConfigWithAutoDownOff = Seq(sensitiveConfig, ConfigFromContents("""akka.cluster.auto-down-unreachable-after = off"""))

  }




  trait WithNode1 extends WithNode {
    private val nodeIdx = 1

    def node1Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node1System = locateExistingSystem(instanceId(nodeIdx))

    def startNode1(configs: ConfigReference*) = startNode(nodeIdx, configs: _*)

    def restartNode1(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def stopNode1() = stopNode(nodeIdx)

    def node1Configs: Seq[ConfigReference] = Seq.empty

    def node1Services: Map[String, Class[_]] = Map.empty

    def onNode1ExpectNoEvents(event: Sysevent, values: FieldAndValue*): Unit = expectNoEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode1ExpectOneOrMoreEvents(event: Sysevent, values: FieldAndValue*): Unit = expectOneOrMoreEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode1ExpectExactlyOneEvent(event: Sysevent, values: FieldAndValue*): Unit = expectExactlyOneEvent(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode1ExpectExactlyNEvents(count: Int, event: Sysevent, values: FieldAndValue*): Unit = expectExactlyNEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode1ExpectNtoMEvents(count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectNtoMEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode1ExpectSomeEventsWithTimeout(timeout: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode1ExpectSomeEventsWithTimeout(timeout: Int, c: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, c, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode1ExpectRangeOfEventsWithTimeout(timeout: Int, count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectRangeOfEventsWithTimeout(timeout, count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    val node1BootstrapRef = startNode1(serviceClassesToConfig(node1Services) +: node1Configs: _*)

    def sendToServiceOnNode1(id: String, m: Any) = node1BootstrapRef ! ForwardToService(id, m)

    def atNode1BlockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode1("mgmt", Block(portFor(i))) }
    def atNode1UnblockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode1("mgmt", Unblock(portFor(i))) }

  }

  trait WithNode2 extends WithNode {
    private val nodeIdx = 2

    def node2Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node2System = locateExistingSystem(instanceId(nodeIdx))

    def startNode2(configs: ConfigReference*) = startNode(nodeIdx, configs: _*)

    def restartNode2(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def stopNode2() = stopNode(nodeIdx)

    def node2Configs: Seq[ConfigReference] = Seq.empty

    def node2Services: Map[String, Class[_]] = Map.empty

    def onNode2ExpectNoEvents(event: Sysevent, values: FieldAndValue*): Unit = expectNoEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode2ExpectOneOrMoreEvents(event: Sysevent, values: FieldAndValue*): Unit = expectOneOrMoreEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode2ExpectExactlyOneEvent(event: Sysevent, values: FieldAndValue*): Unit = expectExactlyOneEvent(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode2ExpectExactlyNEvents(count: Int, event: Sysevent, values: FieldAndValue*): Unit = expectExactlyNEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode2ExpectNtoMEvents(count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectNtoMEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode2ExpectSomeEventsWithTimeout(timeout: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode2ExpectSomeEventsWithTimeout(timeout: Int, c: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, c, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode2ExpectRangeOfEventsWithTimeout(timeout: Int, count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectRangeOfEventsWithTimeout(timeout, count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)


    val node2BootstrapRef = startNode2(serviceClassesToConfig(node2Services) +: node2Configs: _*)

    def sendToServiceOnNode2(id: String, m: Any) = node2BootstrapRef ! ForwardToService(id, m)

    def atNode2BlockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode2("mgmt", Block(portFor(i))) }
    def atNode2UnblockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode2("mgmt", Unblock(portFor(i))) }

  }

  trait WithNode3 extends WithNode {
    private val nodeIdx = 3

    def node3Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node3System = locateExistingSystem(instanceId(nodeIdx))

    def startNode3(configs: ConfigReference*) = startNode(nodeIdx, configs: _*)

    def restartNode3(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def stopNode3() = stopNode(nodeIdx)

    def node3Configs: Seq[ConfigReference] = Seq.empty

    def node3Services: Map[String, Class[_]] = Map.empty

    def onNode3ExpectNoEvents(event: Sysevent, values: FieldAndValue*): Unit = expectNoEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode3ExpectOneOrMoreEvents(event: Sysevent, values: FieldAndValue*): Unit = expectOneOrMoreEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode3ExpectExactlyOneEvent(event: Sysevent, values: FieldAndValue*): Unit = expectExactlyOneEvent(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode3ExpectExactlyNEvents(count: Int, event: Sysevent, values: FieldAndValue*): Unit = expectExactlyNEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode3ExpectNtoMEvents(count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectNtoMEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode3ExpectSomeEventsWithTimeout(timeout: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode3ExpectSomeEventsWithTimeout(timeout: Int, c: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, c, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode3ExpectRangeOfEventsWithTimeout(timeout: Int, count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectRangeOfEventsWithTimeout(timeout, count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)


    val node3BootstrapRef = startNode3(serviceClassesToConfig(node3Services) +: node3Configs: _*)

    def sendToServiceOnNode3(id: String, m: Any) = node3BootstrapRef ! ForwardToService(id, m)

    def atNode3BlockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode3("mgmt", Block(portFor(i))) }
    def atNode3UnblockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode3("mgmt", Unblock(portFor(i))) }

  }

  trait WithNode4 extends WithNode {
    private val nodeIdx = 4

    def node4Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node4System = locateExistingSystem(instanceId(nodeIdx))

    def startNode4(configs: ConfigReference*) = startNode(nodeIdx, configs: _*)

    def restartNode4(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def stopNode4() = stopNode(nodeIdx)

    def node4Configs: Seq[ConfigReference] = Seq.empty

    def node4Services: Map[String, Class[_]] = Map.empty

    def onNode4ExpectNoEvents(event: Sysevent, values: FieldAndValue*): Unit = expectNoEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode4ExpectOneOrMoreEvents(event: Sysevent, values: FieldAndValue*): Unit = expectOneOrMoreEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode4ExpectExactlyOneEvent(event: Sysevent, values: FieldAndValue*): Unit = expectExactlyOneEvent(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode4ExpectExactlyNEvents(count: Int, event: Sysevent, values: FieldAndValue*): Unit = expectExactlyNEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode4ExpectNtoMEvents(count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectNtoMEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode4ExpectSomeEventsWithTimeout(timeout: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode4ExpectSomeEventsWithTimeout(timeout: Int, c: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, c, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode4ExpectRangeOfEventsWithTimeout(timeout: Int, count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectRangeOfEventsWithTimeout(timeout, count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)


    val node4BootstrapRef = startNode4(serviceClassesToConfig(node4Services) +: node4Configs: _*)

    def sendToServiceOnNode4(id: String, m: Any) = node4BootstrapRef ! ForwardToService(id, m)

    def atNode4BlockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode4("mgmt", Block(portFor(i))) }
    def atNode4UnblockNode(idx: Int*) = idx.foreach { i => sendToServiceOnNode4("mgmt", Unblock(portFor(i))) }

  }

  trait WithNode5 extends WithNode {
    private val nodeIdx = 5

    def node5Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node5System = locateExistingSystem(instanceId(nodeIdx))

    def startNode5(configs: ConfigReference*) = startNode(nodeIdx, configs: _*)

    def restartNode5(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def node5Configs: Seq[ConfigReference] = Seq.empty

    def node5Services: Map[String, Class[_]] = Map.empty

    def onNode5ExpectNoEvents(event: Sysevent, values: FieldAndValue*): Unit = expectNoEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode5ExpectOneOrMoreEvents(event: Sysevent, values: FieldAndValue*): Unit = expectOneOrMoreEvents(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode5ExpectExactlyOneEvent(event: Sysevent, values: FieldAndValue*): Unit = expectExactlyOneEvent(event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode5ExpectExactlyNEvents(count: Int, event: Sysevent, values: FieldAndValue*): Unit = expectExactlyNEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode5ExpectNtoMEvents(count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectNtoMEvents(count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode5ExpectSomeEventsWithTimeout(timeout: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode5ExpectSomeEventsWithTimeout(timeout: Int, c: Int, event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(timeout, c, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)

    def onNode5ExpectRangeOfEventsWithTimeout(timeout: Int, count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectRangeOfEventsWithTimeout(timeout, count, event, ('nodeid -> nodeId(nodeIdx)) +: values: _*)


    val node5BootstrapRef = startNode5(serviceClassesToConfig(node5Services) +: node5Configs: _*)

    def sendToServiceOnNode5(id: String, m: Any) = node5BootstrapRef ! ForwardToService(id, m)
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

  trait With5Nodes extends With4Nodes with WithNode5 {
    override def expectFullyBuilt(): Unit = {
      super.expectFullyBuilt()
      onNode5ExpectSomeEventsWithTimeout(20000, ServiceNodeSysevents.StateChange, 'to -> "FullyBuilt")
    }
  }

}
