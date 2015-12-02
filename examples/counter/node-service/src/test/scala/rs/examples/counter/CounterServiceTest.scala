package rs.examples.counter

import rs.node.core.ServiceNodeActor
import rs.testing.components.TestServiceConsumer
import rs.testing.components.TestServiceConsumer.{Open, SendSignal}
import rs.testing.{ConfigFromContents, ConfigReference, StandardMultiNodeSpec}

import scala.concurrent.duration._
import scala.language.postfixOps

class CounterServiceTest extends StandardMultiNodeSpec {

  trait WithServiceOnNode11AndConsumerOnNode2 extends With2Nodes {
    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node1Services = super.node1Services + ("counter-service" -> classOf[CounterService])

    override def node2Services = super.node2Services + ("consumer-service" -> classOf[TestServiceConsumer])

    on node1 expectOne of ServiceNodeActor.Evt.StartingService + ('service -> "counter-service")
    on node2 expectOne of ServiceNodeActor.Evt.StartingService + ('service -> "consumer-service")

    def consumer = serviceOnNode2("consumer-service")
  }

  "Counter service" should "start on node1" in new WithServiceOnNode11AndConsumerOnNode2 {
    on node1 expectOne of CounterServiceEvt.ServiceRunning
  }

  it should "auto transition to Ticking state with initial value of 0" in new WithServiceOnNode11AndConsumerOnNode2 {
    on node1 expectOne of CounterServiceEvt.NowTicking + ('current -> 0)
  }

  def registerAndUpdateCheck = new WithServiceOnNode11AndConsumerOnNode2 {
    consumer ! Open("counter-service", "counter")
    on node1 expectOne of CounterServiceEvt.StreamInterestAdded + ('stream -> "counter")
    on node2 expectSome of TestServiceConsumer.Evt.StringUpdate + ('topic -> "counter")
  }

  it should s"register a subscriber and send stream updates, which should be received by consumer" in registerAndUpdateCheck
  for (i <- 1 to 5) it should s"have a consistent behavior. Check $i" in registerAndUpdateCheck

  trait WithOneSubscriber extends WithServiceOnNode11AndConsumerOnNode2 {
    consumer ! Open("counter-service", "counter")
    on node1 expectOne of CounterServiceEvt.StreamInterestAdded + ('stream -> "counter")
    on node2 expectSome of TestServiceConsumer.Evt.StringUpdate + ('topic -> "counter")
    clearEvents()
  }

  it should "send correct counter values after reset" in new WithOneSubscriber {
    consumer ! SendSignal("counter-service", "reset")
    on node2 expectOne of TestServiceConsumer.Evt.StringUpdate +('topic -> "counter", 'value -> "0")
    on node2 expectOne of TestServiceConsumer.Evt.StringUpdate +('topic -> "counter", 'value -> "1")
  }

  it should "stop sending updates if stopped" in new WithOneSubscriber {
    consumer ! SendSignal("counter-service", "stop")
    on node1 expectOne of CounterServiceEvt.NowStopped
    clearEvents()

    within(5 seconds) {
      on node2 expectNone of TestServiceConsumer.Evt.StringUpdate + ('topic -> "counter")
    }
  }

  it should "resume updates when restarted" in new WithOneSubscriber {
    on node2 expectSome of TestServiceConsumer.Evt.StringUpdate + ('topic -> "counter")

    consumer ! SendSignal("counter-service", "stop")
    on node1 expectOne of CounterServiceEvt.NowStopped

    val lastValue = locateLastEventFieldValue[String](TestServiceConsumer.Evt.StringUpdate, "value").toInt
    clearEvents()

    consumer ! SendSignal("counter-service", "start")
    on node1 expectOne of CounterServiceEvt.NowTicking

    on node2 expectOne of TestServiceConsumer.Evt.StringUpdate + ('topic -> "counter", 'value -> (lastValue + 1).toString)
    on node2 expectNone of TestServiceConsumer.Evt.StringUpdate + ('topic -> "counter", 'value -> lastValue.toString)
  }

  it should "serve any number of subscribers" in new WithOneSubscriber {
    new WithNode3 {
      override def node3Services = super.node3Services + ("consumer-service" -> classOf[TestServiceConsumer])

      on node3 expectOne of ServiceNodeActor.Evt.StartingService + ('service -> "consumer-service")

      def consumer2 = serviceOnNode3("consumer-service")

      consumer2 ! Open("counter-service", "counter")
      on node3 expectSome of TestServiceConsumer.Evt.StringUpdate + ('topic -> "counter")
    }
  }

  "Counter service consumer" should "keep receiving updates after one service instance crashes" in new WithOneSubscriber {
    new WithNode3 {
      override def node3Services = super.node3Services + ("counter-service" -> classOf[CounterService])
      on node3 expectOne of ServiceNodeActor.Evt.StartingService + ('service -> "counter-service")

      clearEvents()
      stopNode1()

      on node1 expectOne of CounterServiceEvt.PostStop
      clearEvents()

      on node2 expectSome of TestServiceConsumer.Evt.StringUpdate + ('topic -> "counter")

    }
  }

}
