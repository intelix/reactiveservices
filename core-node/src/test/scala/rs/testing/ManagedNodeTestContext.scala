package rs.testing

import akka.actor.Props
import core.sysevents.FieldAndValue
import org.scalatest.Suite
import rs.core.bootstrap.ServicesBootstrapActor
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService
import rs.core.sysevents.Sysevent
import rs.core.sysevents.support.EventAssertions
import rs.core.tools.UUIDTools
import rs.node.core.ServiceClusterGuardianActor

trait ManagedNodeTestContext extends MultiActorSystemTestContext with EventAssertions {
  _: Suite with ActorSystemManagement =>

  private def instanceId(idx: Int) = s"node$idx"


  private val basePort = 3800

  private def portFor(idx: Int) = basePort + idx - 1

  private def nodeId(idx: Int) = "Node" + idx


  private def nodeConfig(idx: Int) =
    s"""
       |include "node-defaults"
       |
       |node {
       |  id = "${nodeId(idx)}"
       |  host = "localhost"
       |  port = ${portFor(idx)}
       |  cluster {
       |    core-nodes = [
       |      "localhost:${portFor(1)}"
       |    ]
       |  }
       |}
       |
       |
     """.stripMargin

  trait WithNode {

    def startNode(idx: Int, configs: ConfigReference*) =
      withSystem(instanceId(idx)) { implicit sys =>
        val namespace = UUIDTools.generateShortUUID
        val config = buildConfig(Seq(ConfigFromContents(nodeConfig(idx))) ++ configs: _*)
        sys.start(ServiceClusterGuardianActor.props(config), namespace)
      }

    def restartNode(idx: Int, configs: ConfigReference*) = {
      destroySystem(instanceId(idx))
      startNode(idx, configs: _*)
    }

    protected def serviceClassesToConfig(list: Map[String, Class[_]]) =
      ConfigFromContents(list.map {
        case (k, v) => s"""node.services.$k="${v.getName}""""
      } mkString "\n")
  }

  trait WithNode1 extends WithNode {
    private val nodeIdx = 1

    def node1Address = s"akka.tcp://cluster@localhost:${portFor(nodeIdx)}"

    def node1System = locateExistingSystem(instanceId(nodeIdx))

    def startNode1(configs: ConfigReference*) = startNode(nodeIdx, configs: _*)

    def restartNode1(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

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
  }

  trait WithNode2 extends WithNode {
    private val nodeIdx = 2

    def node2Address = s"akka.tcp://cluster@localhost:${portFor(nodeIdx)}"

    def node2System = locateExistingSystem(instanceId(nodeIdx))

    def startNode2(configs: ConfigReference*) = startNode(nodeIdx, configs: _*)

    def restartNode2(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

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
  }

  trait WithNode3 extends WithNode {
    private val nodeIdx = 3

    def node3Address = s"akka.tcp://cluster@localhost:${portFor(nodeIdx)}"

    def node3System = locateExistingSystem(instanceId(nodeIdx))

    def startNode3(configs: ConfigReference*) = startNode(nodeIdx, configs: _*)

    def restartNode3(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

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
  }

  trait WithNode4 extends WithNode {
    private val nodeIdx = 4

    def node4Address = s"akka.tcp://cluster@localhost:${portFor(nodeIdx)}"

    def node4System = locateExistingSystem(instanceId(nodeIdx))

    def startNode4(configs: ConfigReference*) = startNode(nodeIdx, configs: _*)

    def restartNode4(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

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
  }

  trait WithNode5 extends WithNode {
    private val nodeIdx = 5

    def node5Address = s"akka.tcp://cluster@localhost:${portFor(nodeIdx)}"

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

  trait With2Nodes extends WithNode1 with WithNode2

  trait With3Nodes extends WithNode1 with WithNode2 with WithNode3

  trait With4Nodes extends WithNode1 with WithNode2 with WithNode3 with WithNode4

  trait With5Nodes extends WithNode1 with WithNode2 with WithNode3 with WithNode4 with WithNode5

}
