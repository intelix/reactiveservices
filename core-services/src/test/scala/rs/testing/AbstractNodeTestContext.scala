package rs.testing

import akka.actor.ActorRef
import core.sysevents._
import org.scalatest.Suite
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService
import rs.core.sysevents.Sysevent
import rs.core.sysevents.support.EventAssertions


trait AbstractNodeTestContext {
  _: MultiActorSystemTestContext with EventAssertions with Suite with ActorSystemManagement =>

  protected def instanceId(idx: Int) = s"node$idx"


  protected val basePort = 3800

  protected def portFor(idx: Int) = basePort + idx - 1

  protected def nodeId(idx: Int) = "Node" + idx

  protected def baseConfig(idx: Int): Seq[ConfigReference]


  protected def startWithConfig(idx: Int, configs: ConfigReference*): ActorRef

  protected def serviceClassesToConfig(list: Map[String, Class[_]]) =
    ConfigFromContents(list.map {
      case (k, v) => s"""node.services.$k="${v.getName}""""
    } mkString "\n")

  trait WithNode {

    val protocol = "akka.tcp"

    def allNodesConfigs: Seq[ConfigReference] = Seq.empty

    def allNodesServices: Map[String, Class[_]] = Map.empty

    def startNode(idx: Int, configs: ConfigReference*) =
      startWithConfig(idx, baseConfig(idx) ++ Seq(serviceClassesToConfig(allNodesServices)) ++ allNodesConfigs ++ configs: _*)

    def restartNode(idx: Int, configs: ConfigReference*) = {
      destroySystem(instanceId(idx))
      startNode(idx, configs: _*)
    }

    def stopNode(idx: Int) = {
      destroySystem(instanceId(idx))
    }


  }

  trait WithNode1 extends WithNode {
    private val nodeIdx = 1

    def node1Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node1System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode1(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

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

    var node1BootstrapRef: Option[ActorRef] = None

    def sendToServiceOnNode1(id: String, m: Any) = node1BootstrapRef.foreach(_ ! ForwardToService(id, m))

    def startNode1(): Unit = node1BootstrapRef = startNode1(serviceClassesToConfig(node1Services) +: node1Configs: _*)

    startNode1()


  }

  trait WithNode2 extends WithNode {
    private val nodeIdx = 2

    def node2Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node2System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode2(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

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


    var node2BootstrapRef: Option[ActorRef] = None

    def sendToServiceOnNode2(id: String, m: Any) = node2BootstrapRef.foreach(_ ! ForwardToService(id, m))

    def startNode2(): Unit = node2BootstrapRef = startNode2(serviceClassesToConfig(node2Services) +: node2Configs: _*)

    startNode2()

  }

  trait WithNode3 extends WithNode {
    private val nodeIdx = 3

    def node3Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node3System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode3(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

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


    var node3BootstrapRef: Option[ActorRef] = None

    def sendToServiceOnNode3(id: String, m: Any) = node3BootstrapRef.foreach(_ ! ForwardToService(id, m))

    def startNode3(): Unit = node3BootstrapRef = startNode3(serviceClassesToConfig(node3Services) +: node3Configs: _*)

    startNode3()

  }

  trait WithNode4 extends WithNode {
    private val nodeIdx = 4

    def node4Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node4System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode4(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

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


    var node4BootstrapRef: Option[ActorRef] = None

    def sendToServiceOnNode4(id: String, m: Any) = node4BootstrapRef.foreach(_ ! ForwardToService(id, m))

    def startNode4(): Unit = node4BootstrapRef = startNode4(serviceClassesToConfig(node4Services) +: node4Configs: _*)

    startNode4()

  }

  trait WithNode5 extends WithNode {
    private val nodeIdx = 5

    def node5Address = s"$protocol://cluster@localhost:${portFor(nodeIdx)}"

    def node5System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode5(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

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


    var node5BootstrapRef: Option[ActorRef] = None

    def sendToServiceOnNode5(id: String, m: Any) = node5BootstrapRef.foreach(_ ! ForwardToService(id, m))

    def startNode5(): Unit = node5BootstrapRef = startNode5(serviceClassesToConfig(node5Services) +: node5Configs: _*)

    startNode5()
  }


}
