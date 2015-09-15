package rs.testing

import akka.actor.Props
import core.sysevents.FieldAndValue
import org.scalatest.Suite
import rs.core.bootstrap.ServicesBootstrapActor
import rs.core.sysevents.Sysevent
import rs.core.sysevents.support.EventAssertions
import rs.core.tools.UUIDTools

trait UnmanagedNodeTestContext extends MultiActorSystemTestContext with EventAssertions {
  _: Suite with ActorSystemManagement =>

  private def instanceId(idx: Int) = s"node$idx"


  private val basePort = 3800

  private def portFor(idx: Int) = basePort + idx - 1

  private def nodeId(idx: Int) = "Node" + idx

  private val seedConfig =
    s"""
       |include "common-clustered-process"
       |akka {
       |  cluster {
       |    seed-nodes = ["akka.tcp://cluster@localhost:${portFor(1)}"]
       |  }
       |}
       |
    """.stripMargin

  private def nodeConfig(idx: Int) =
    s"""
       |node {
       |  id = "${nodeId(idx)}"
       |  host = "localhost"
       |  port = ${portFor(idx)}
       |}
       |
     """.stripMargin + seedConfig

  trait WithNode {
    def startNode(idx: Int, configs: ConfigReference*) =
      withSystem(instanceId(idx),
        Seq(
          ConfigFromContents(nodeConfig(idx)),
          ConfigFromFile("registry-defaults")
        ) ++ configs: _*) { implicit sys =>
        sys.start(Props[ServicesBootstrapActor], UUIDTools.generateShortUUID)
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


    startNode1(serviceClassesToConfig(node1Services) +: node1Configs: _*)
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



    startNode2(serviceClassesToConfig(node2Services) +: node2Configs: _*)
  }


}
