package rs.core

import akka.actor.Props
import org.scalatest.FlatSpec
import rs.core.actors.{ActorWithComposableBehavior, BaseActorSysevents, ClusterAwareness}
import rs.core.sysevents.support.EventAssertions
import rs.testing.{ConfigFromFile, MultiActorSystemTestContext, SharedActorSystem}


trait TestActorEvents extends BaseActorSysevents {

  val LeaderChanged = "LeaderChanged".info

  override def componentId: String = "Test"
}

object TestActorEvents extends TestActorEvents

class TestActor extends ActorWithComposableBehavior with TestActorEvents with ClusterAwareness {

  override def onLeaderChanged(): Unit = LeaderChanged('self -> cluster.selfAddress.toString)
}


class BaseTestingFrameworkTest extends FlatSpec with MultiActorSystemTestContext with EventAssertions with SharedActorSystem {


  "Cluster of three nodes" should "form when starting manually with blank nodes" in {
    withSystem("node1", ConfigFromFile("blank-node1")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    withSystem("node2", ConfigFromFile("blank-node2")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    withSystem("node3", ConfigFromFile("blank-node3")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    expectSomeEventsWithTimeout(10000, 1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3801")
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3802")
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3803")
  }

  it should "form again for the next test" in {
    withSystem("node1", ConfigFromFile("blank-node1")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    withSystem("node2", ConfigFromFile("blank-node2")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    withSystem("node3", ConfigFromFile("blank-node3")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3801")
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3802")
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3803")
  }

  it should "form again no matter how many tests are in the suite" in {
    withSystem("node1", ConfigFromFile("blank-node1")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    withSystem("node2", ConfigFromFile("blank-node2")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    withSystem("node3", ConfigFromFile("blank-node3")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3801")
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3802")
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3803")
  }

  "Cluster of four nodes" should "form after tests with only three nodes " in {
    withSystem("node1", ConfigFromFile("blank-node1")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    withSystem("node2", ConfigFromFile("blank-node2")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    withSystem("node3", ConfigFromFile("blank-node3")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    withSystem("node4", ConfigFromFile("blank-node4")) { sys =>
      sys.start(Props[TestActor], "test")
    }
    expectSomeEventsWithTimeout(10000, 1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3804")
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3802")
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3803")
    expectExactlyNEvents(1, TestActorEvents.LeaderChanged, 'self -> "akka.tcp://cluster@localhost:3801")
  }

}
