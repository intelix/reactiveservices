package rs.core

import org.scalatest.FlatSpec
import rs.node.core.{ServiceNodeSysevents, ServiceClusterGuardianSysevents}
import rs.testing.{SharedActorSystem, NodeTestContext}

object Evt extends ServiceNodeSysevents

class ClusterFormationTest extends FlatSpec with NodeTestContext with SharedActorSystem {

  import Evt._

  "Cluster" should "start with 4 nodes and all peers should be discovered" in new WithNode1 with WithNode2 {
    startNode1()
    startNode2()

    expectSomeEventsWithTimeout(30000, 2, StateChange, 'to -> "FullyBuilt")
  }


  trait CContext extends WithNode1 with WithNode2 {
    startNode1()
    startNode2()
  }

  it should "start again" in new CContext {
    expectSomeEventsWithTimeout(30000, 2, StateChange, 'to -> "FullyBuilt")
    collectAndPrintEvents()
  }

}
