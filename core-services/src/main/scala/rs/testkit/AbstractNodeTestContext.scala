package rs.testkit

import akka.actor.ActorRef
import org.scalatest.Suite
import rs.core.bootstrap.ServicesBootstrapActor.ForwardToService

import scala.language.implicitConversions


trait AbstractNodeTestContext {
  _: MultiActorSystemTestContext with EvtAssertions with Suite with ActorSystemManagement =>

  protected def instanceId(idx: Int) = s"node$idx"


  protected val basePort = 3800

  protected def akkaPortFor(idx: Int) = basePort + idx - 1

  protected def nodeId(idx: Int) = "Node" + idx

  protected def baseConfig(idx: Int): Seq[ConfigReference]

  class OnNodeX(i: Int) {
    val fields = Seq('nodeid -> nodeId(i))
  }


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

  case class ServiceRef(id: String, ref: Option[ActorRef]) {
    def !(m: Any) = ref.foreach(_ ! ForwardToService(id, m))
  }

  trait WithNode1 extends WithNode {
    private val nodeIdx = 1

    case class OnNode1() extends OnNodeX(1) {
      def node1(e: BaseExpectation): ExecutableExpectation = ExecutableExpectation(e, fields)
    }

    implicit def convertToOnNode1(s: EventAssertionKey): OnNode1 = OnNode1()


    def node1Address = s"$protocol://cluster@localhost:${akkaPortFor(nodeIdx)}"

    def node1System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode1(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

    def restartNode1(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def stopNode1() = stopNode(nodeIdx)

    def node1Configs: Seq[ConfigReference] = Seq(
      ConfigFromContents( """akka.cluster.roles+="wCritical""""),
      ConfigFromContents( """akka.cluster.roles+="Region1""""),
      ConfigFromContents( """akka.cluster.roles+="seed"""")
    )

    def node1Services: Map[String, Class[_]] = Map.empty

    var node1BootstrapRef: Option[ActorRef] = None

    def serviceOnNode1(id: String) = ServiceRef(id, node1BootstrapRef)

    def startNode1(): Unit = node1BootstrapRef = startNode1(serviceClassesToConfig(node1Services) +: node1Configs: _*)

    startNode1()


  }

  trait WithNode2 extends WithNode {
    private val nodeIdx = 2

    case class OnNode2() extends OnNodeX(2) {
      def node2(e: BaseExpectation): ExecutableExpectation = ExecutableExpectation(e, fields)
    }

    implicit def convertToOnNode2(s: EventAssertionKey): OnNode2 = OnNode2()

    def node2Address = s"$protocol://cluster@localhost:${akkaPortFor(nodeIdx)}"

    def node2System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode2(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

    def restartNode2(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def stopNode2() = stopNode(nodeIdx)

    def node2Configs: Seq[ConfigReference] = Seq(
      ConfigFromContents( """akka.cluster.roles+="wPreferred""""),
      ConfigFromContents( """akka.cluster.roles+="Region2""""),
      ConfigFromContents( """akka.cluster.roles+="seed"""")
    )

    def node2Services: Map[String, Class[_]] = Map.empty

    var node2BootstrapRef: Option[ActorRef] = None

    def serviceOnNode2(id: String) = ServiceRef(id, node2BootstrapRef)

    def startNode2(): Unit = node2BootstrapRef = startNode2(serviceClassesToConfig(node2Services) +: node2Configs: _*)

    startNode2()

  }

  trait WithNode3 extends WithNode {
    private val nodeIdx = 3

    case class OnNode3() extends OnNodeX(3) {
      def node3(e: BaseExpectation): ExecutableExpectation = ExecutableExpectation(e, fields)
    }

    implicit def convertToOnNode3(s: EventAssertionKey): OnNode3 = OnNode3()

    def node3Address = s"$protocol://cluster@localhost:${akkaPortFor(nodeIdx)}"

    def node3System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode3(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

    def restartNode3(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def stopNode3() = stopNode(nodeIdx)

    def node3Configs: Seq[ConfigReference] = Seq.empty

    def node3Services: Map[String, Class[_]] = Map.empty

    var node3BootstrapRef: Option[ActorRef] = None

    def serviceOnNode3(id: String) = ServiceRef(id, node3BootstrapRef)

    def startNode3(): Unit = node3BootstrapRef = startNode3(serviceClassesToConfig(node3Services) +: node3Configs: _*)

    startNode3()

  }

  trait WithNode4 extends WithNode {
    private val nodeIdx = 4

    case class OnNode4() extends OnNodeX(4) {
      def node4(e: BaseExpectation): ExecutableExpectation = ExecutableExpectation(e, fields)
    }

    implicit def convertToOnNode4(s: EventAssertionKey): OnNode4 = OnNode4()


    def node4Address = s"$protocol://cluster@localhost:${akkaPortFor(nodeIdx)}"

    def node4System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode4(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

    def restartNode4(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def stopNode4() = stopNode(nodeIdx)

    def node4Configs: Seq[ConfigReference] = Seq.empty

    def node4Services: Map[String, Class[_]] = Map.empty

    var node4BootstrapRef: Option[ActorRef] = None

    def serviceOnNode4(id: String) = ServiceRef(id, node4BootstrapRef)

    def startNode4(): Unit = node4BootstrapRef = startNode4(serviceClassesToConfig(node4Services) +: node4Configs: _*)

    startNode4()

  }

  trait WithNode5 extends WithNode {
    private val nodeIdx = 5

    case class OnNode5() extends OnNodeX(5) {
      def node5(e: BaseExpectation): ExecutableExpectation = ExecutableExpectation(e, fields)
    }

    implicit def convertToOnNode5(s: EventAssertionKey): OnNode5 = OnNode5()


    def node5Address = s"$protocol://cluster@localhost:${akkaPortFor(nodeIdx)}"

    def node5System = locateExistingSystem(instanceId(nodeIdx))

    private def startNode5(configs: ConfigReference*) = Some(startNode(nodeIdx, configs: _*))

    def restartNode5(configs: ConfigReference*) = restartNode(nodeIdx, configs: _*)

    def node5Configs: Seq[ConfigReference] = Seq.empty

    def node5Services: Map[String, Class[_]] = Map.empty

    var node5BootstrapRef: Option[ActorRef] = None

    def serviceOnNode5(id: String) = ServiceRef(id, node5BootstrapRef)

    def startNode5(): Unit = node5BootstrapRef = startNode5(serviceClassesToConfig(node5Services) +: node5Configs: _*)

    startNode5()
  }


}
