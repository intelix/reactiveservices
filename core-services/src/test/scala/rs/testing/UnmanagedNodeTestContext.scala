package rs.testing

import akka.actor.{ActorRef, Props}
import org.scalatest.Suite
import rs.core.sysevents.support.EventAssertions
import rs.core.tools.UUIDTools

trait UnmanagedNodeTestContext extends MultiActorSystemTestContext with EventAssertions with AbstractNodeTestContext {
  _: Suite with ActorSystemManagement =>

  private val seedConfig =
    s"""
       |akka {
       |
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
       |
       |  cluster {
       |    log-info = off
       |    seed-nodes = ["akka.tcp://cluster@localhost:${portFor(1)}", "akka.tcp://cluster@localhost:${portFor(3)}"]
       |    gossip-interval = 300ms
       |    failure-detector {
       |      threshold = 1
       |      min-std-deviation = 100 ms
       |      acceptable-heartbeat-pause = 3 s
       |      expected-response-after = 2 s
       |    }
       |    seed-node-timeout = 2s
       |    retry-unsuccessful-join-after = 1s
       |    auto-down-unreachable-after = 1s
       |  }
       |}
       |    """.stripMargin

  private def nodeConfig(idx: Int) =
    s"""
       |node {
       |  id = "${nodeId(idx)}"
       |  host = "localhost"
       |  port = ${portFor(idx)}
       |}
       |
     """.stripMargin + seedConfig


  override protected def startWithConfig(idx: Int, configs: ConfigReference*): ActorRef =
    withSystem(instanceId(idx)) { implicit sys =>
      val config = buildConfig(configs: _*)
      sys.start(Props(classOf[BasicClusterBootstrap], config), UUIDTools.generateShortUUID)
    }


  override protected def baseConfig(idx: Int): Seq[ConfigReference] = Seq(
    ConfigFromFile("common-clustered-process.conf"),
    ConfigFromContents(nodeConfig(idx)),
    ConfigFromFile("registry-defaults")
  )

  trait With2Nodes extends WithNode1 with WithNode2

  trait With3Nodes extends WithNode1 with WithNode2 with WithNode3

  trait With4Nodes extends WithNode1 with WithNode2 with WithNode3 with WithNode4

  trait With5Nodes extends WithNode1 with WithNode2 with WithNode3 with WithNode4 with WithNode5

}
