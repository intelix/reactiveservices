package rs.core

import akka.actor.PoisonPill
import org.scalatest.FlatSpec
import rs.core.SubjectKeys.UserId
import rs.core.bootstrap.ServicesBootstrapEvents
import rs.core.registry.ServiceRegistrySysevents
import rs.core.services.StreamId
import rs.testing.components.TestServiceActor.{Evt, PublishString}
import rs.testing.components.TestServiceConsumer.{SendSignal, Close, Open}
import rs.testing.components.{TestServiceConsumer, ClusterAwareService, TestServiceActor}
import rs.testing.{ConfigFromContents, ConfigReference, IsolatedActorSystems, UnmanagedNodeTestContext}

import scala.concurrent.duration._
import scala.language.postfixOps

class CoreServiceTest extends FlatSpec with UnmanagedNodeTestContext with IsolatedActorSystems {


  trait With4NodesAndTestOn1 extends With4Nodes {
    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor])
  }

  trait WithClusterAwareServiceOn1 extends WithNode1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services + ("test" -> classOf[ClusterAwareService])
  }

  trait WithClusterAwareServiceOn2 extends WithNode2 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test" -> classOf[ClusterAwareService])
  }

  trait WithClusterAwareServiceOn3 extends WithNode3 {
    override def node3Services: Map[String, Class[_]] = super.node3Services + ("test" -> classOf[ClusterAwareService])
  }

  "Cluster-aware service on single node" should "receive MemberUp notification from own node" in new WithClusterAwareServiceOn1 {
    onNode1ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node1Address)
  }
  it should "become a leader" in new WithClusterAwareServiceOn1 {
    onNode1ExpectExactlyOneEvent(ClusterAwareService.Evt.LeaderChanged, 'addr -> s"Some($node1Address)")
    onNode1ExpectExactlyOneEvent(ClusterAwareService.Evt.LeaderTakeover)
  }

  "Cluster-aware service on two nodes" should "receive MemberUp notification from both nodes" in new WithClusterAwareServiceOn1 with WithClusterAwareServiceOn2 {
    onNode1ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node1Address)
    onNode1ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node2Address)
    onNode2ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node1Address)
    onNode2ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node2Address)
  }

  trait WithClusterAwareServiceRunningOnTwo extends WithClusterAwareServiceOn1 with WithClusterAwareServiceOn2 {
    onNode1ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node1Address)
    onNode1ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node2Address)
    onNode2ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node1Address)
    onNode2ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node2Address)
    clearEvents()
  }

  trait WithClusterAwareServiceRunningOnThree extends WithClusterAwareServiceRunningOnTwo with WithClusterAwareServiceOn3 {
    onNode3ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node1Address)
    onNode3ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberUp, 'addr -> node2Address)
    clearEvents()
  }

  it should "receive MemberUnreachable followed by MemberRemoved when another node dies" in new WithClusterAwareServiceRunningOnTwo {
    stopNode1()
    onNode2ExpectSomeEventsWithTimeout(15000, ClusterAwareService.Evt.MemberUnreachable, 'addr -> node1Address)
    onNode2ExpectExactlyOneEvent(ClusterAwareService.Evt.MemberRemoved, 'addr -> node1Address)
  }

  it should "transfer leadership when one node dies" in new WithClusterAwareServiceRunningOnTwo {
    stopNode1()
    onNode2ExpectSomeEventsWithTimeout(15000, ClusterAwareService.Evt.LeaderChanged, 'addr -> node2Address.r)
    onNode2ExpectExactlyOneEvent(ClusterAwareService.Evt.LeaderTakeover)
  }


  "Service configuration" should "be accessible from the service" in new With4NodesAndTestOn1 {
    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+ ConfigFromContents("test.int-config-value=123")

    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.IntConfigValue, 'value -> 123)
  }


  "Service running on Node1" should "start and create remote endpoints" in new With4NodesAndTestOn1 {
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.RemoteEndpointRegistered, 'location -> node1Address)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.RemoteEndpointRegistered, 'location -> node2Address)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.RemoteEndpointRegistered, 'location -> node3Address)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.RemoteEndpointRegistered, 'location -> node4Address)
  }

  it should "start and create remote endpoints on nodes joined later" in new With4NodesAndTestOn1 {
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.RemoteEndpointRegistered, 'location -> node1Address)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.RemoteEndpointRegistered, 'location -> node2Address)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.RemoteEndpointRegistered, 'location -> node3Address)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.RemoteEndpointRegistered, 'location -> node4Address)
    clearEvents()
    new WithNode5 {
      onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.RemoteEndpointRegistered, 'location -> node5Address)
    }
  }

  it should "register its remote endpoints with local registries on each node" in new With4NodesAndTestOn1 {
    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
  }

  it should "not conflict with another service with the same name started on some other node" in new With4NodesAndTestOn1 {
    override def node2Services = super.node2Services ++ Map("test" -> classOf[TestServiceActor])

    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test", 'locations -> 2)
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test", 'locations -> 2)
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test", 'locations -> 2)
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test", 'locations -> 2)

    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.PublishingNewServiceLocation, 'key -> "test")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.PublishingNewServiceLocation, 'key -> "test")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.PublishingNewServiceLocation, 'key -> "test")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.PublishingNewServiceLocation, 'key -> "test")

  }

  it should "remove remote endpoints and unregister location from local registries upon termination" in new With4NodesAndTestOn1 {

    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceRegistered, 'key -> "test")

    sendToServiceOnNode1("test", PoisonPill)

    onNode1ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceUnregistered, 'key -> "test", 'remainingLocations -> 0)
    onNode2ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceUnregistered, 'key -> "test", 'remainingLocations -> 0)
    onNode3ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceUnregistered, 'key -> "test", 'remainingLocations -> 0)
    onNode4ExpectExactlyOneEvent(ServiceRegistrySysevents.ServiceUnregistered, 'key -> "test", 'remainingLocations -> 0)
  }

  it should "be able to register for other service location updates, and get update when service become available" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test1" -> classOf[TestServiceActor])

    onNode1ExpectExactlyOneEvent(Evt.OtherServiceLocationChanged, 'addr -> "Some".r, 'service -> "test1")
  }

  it should "be able to register for other service location updates, and get update when service become unavailable" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test1" -> classOf[TestServiceActor])

    onNode1ExpectExactlyOneEvent(Evt.OtherServiceLocationChanged, 'addr -> "Some".r, 'service -> "test1")
    clearEvents()
    stopNode2()
    onNode1ExpectExactlyOneEvent(Evt.OtherServiceLocationChanged, 'addr -> "None", 'service -> "test1")
  }

  it should "not receive other service location updates if previously selected location is still active, even when new locations become available" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test1" -> classOf[TestServiceActor])

    onNode1ExpectExactlyOneEvent(Evt.OtherServiceLocationChanged, 'addr -> "Some".r, 'service -> "test1")
    clearEvents()
    new WithNode5 {
      override def node5Services: Map[String, Class[_]] = super.node5Services + ("test1" -> classOf[TestServiceActor])

      onNode5ExpectExactlyOneEvent(Evt.OtherServiceLocationChanged, 'addr -> "Some".r, 'service -> "test1")
      duringPeriodInMillis(3000) {
        onNode1ExpectNoEvents(Evt.OtherServiceLocationChanged, 'addr -> "Some".r, 'service -> "test1")
      }
    }
  }

  it should "receive other service location updates if previously selected location becomes unavailable and other service locations are available" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test1" -> classOf[TestServiceActor])

    onNode1ExpectExactlyOneEvent(Evt.OtherServiceLocationChanged, 'addr -> "Some".r, 'service -> "test1")
    clearEvents()
    new WithNode5 {
      override def node5Services: Map[String, Class[_]] = super.node5Services + ("test1" -> classOf[TestServiceActor])

      onNode5ExpectExactlyOneEvent(Evt.OtherServiceLocationChanged, 'addr -> "Some".r, 'service -> "test1")
      duringPeriodInMillis(3000) {
        onNode1ExpectNoEvents(Evt.OtherServiceLocationChanged, 'addr -> "Some".r, 'service -> "test1")
      }

      stopNode2()
      onNode1ExpectExactlyOneEvent(Evt.OtherServiceLocationChanged, 'addr -> "Some".r, 'service -> "test1")
    }
  }



  "Service consumer" should "be able to open stream and receive an update when started on the same node" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services + ("consumer" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer")
    clearEvents()
    sendToServiceOnNode1("consumer", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }
  it should "be able to open stream and receive an update when started on another node" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("consumer" -> classOf[TestServiceConsumer])

    onNode2ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer")
    clearEvents()
    sendToServiceOnNode2("consumer", Open("test", "string"))
    onNode2ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }
  "Multiple service consumers" should "be able to open stream and receive an update when started on the same node" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer2")
    clearEvents()
    sendToServiceOnNode1("consumer1", Open("test", "string"))
    sendToServiceOnNode1("consumer2", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }

  it should "be able to open stream and receive an update when started on different hosts" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    override def node2Services: Map[String, Class[_]] = super.node1Services +("consumer3" -> classOf[TestServiceConsumer], "consumer4" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer2")
    onNode2ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer3")
    onNode2ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer4")
    clearEvents()
    sendToServiceOnNode1("consumer1", Open("test", "string"))
    sendToServiceOnNode1("consumer2", Open("test", "string"))
    sendToServiceOnNode2("consumer3", Open("test", "string"))
    sendToServiceOnNode2("consumer4", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode2ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode2ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer4", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }


  "Service" should "reject invalid subjects" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")

    sendToServiceOnNode1("consumer1", Open("test", "invalid"))

    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.SubjectMappingError)

  }

  it should "not receive mapping request from remote endpoint if subject has already been mapped" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    sendToServiceOnNode1("consumer1", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.SubjectMapped, 'subj -> "test|string")

    clearEvents()
    sendToServiceOnNode1("consumer2", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    onNode1ExpectNoEvents(TestServiceActor.Evt.SubjectMapped, 'subj -> "test|string")
  }


  it should "map multiple subjects to a single stream" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    sendToServiceOnNode1("consumer1", Open("test", "string"))
    sendToServiceOnNode1("consumer1", Open("test", "string1"))
    sendToServiceOnNode1("consumer2", Open("test", "string2"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string1", 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string2", 'value -> TestServiceActor.AutoStringReply)

    clearEvents()

    sendToServiceOnNode1("test", PublishString("string", "latest"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> "latest")
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string1", 'value -> "latest")
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string2", 'value -> "latest")

  }

  it should "close stream when all subjects mapped to the stream have been discarded" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    sendToServiceOnNode1("consumer1", Open("test", "string"))
    sendToServiceOnNode1("consumer1", Open("test", "string1"))
    sendToServiceOnNode1("consumer2", Open("test", "string2"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string1", 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string2", 'value -> TestServiceActor.AutoStringReply)

    clearEvents()

    sendToServiceOnNode1("consumer1", Close("test", "string"))
    sendToServiceOnNode1("consumer1", Close("test", "string1"))

    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(TestServiceActor.Evt.IdleStream)
    }
    clearEvents()

    sendToServiceOnNode1("consumer2", Close("test", "string2"))
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.IdleStream)

  }

  it should "support compound stream id" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    sendToServiceOnNode1("consumer1", Open("test", "stringWithId", UserId("id1")))
    sendToServiceOnNode1("consumer1", Open("test", "stringWithId", UserId("id2")))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id1"), 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id2"),'value -> TestServiceActor.AutoStringReply)

    clearEvents()

    sendToServiceOnNode1("test", PublishString(StreamId("string", Some("id1")), "latest"))

    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id1"), 'value -> "latest")
    duringPeriodInMillis(3000) {
      onNode1ExpectNoEvents(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id2"), 'value -> "latest")

    }

  }

  it should "notify when stream becomes active" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    sendToServiceOnNode1("consumer1", Open("test", "stringWithId", UserId("id1")))

    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.StreamActive, 'stream -> "string#id1")

  }

  it should "notify when stream becomes passive" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    sendToServiceOnNode1("consumer1", Open("test", "stringWithId", UserId("id1")))


    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id1"), 'value -> TestServiceActor.AutoStringReply)
    clearEvents()

    sendToServiceOnNode1("consumer1", Close("test", "stringWithId", UserId("id1")))

    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.StreamPassive, 'stream -> "string#id1")
  }


  trait ServiceWith4ActiveConsumers extends With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    override def node2Services: Map[String, Class[_]] = super.node1Services +("consumer3" -> classOf[TestServiceConsumer], "consumer4" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer2")
    onNode2ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer3")
    onNode2ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer4")

    sendToServiceOnNode1("consumer1", Open("test", "string"))
    sendToServiceOnNode1("consumer2", Open("test", "string"))
    sendToServiceOnNode2("consumer3", Open("test", "string"))
    sendToServiceOnNode2("consumer4", Open("test", "string"))

    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode2ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode2ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer4", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    clearEvents()
  }

  it should "shut down stream at location when all consumers are gone from that location" in new ServiceWith4ActiveConsumers {
    sendToServiceOnNode2("consumer3", PoisonPill)
    sendToServiceOnNode2("consumer4", PoisonPill)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.StreamInterestRemoved, 'location -> node2Address)
    printRaisedEvents()
  }

  it should "shut down stream when all consumers are gone" in new ServiceWith4ActiveConsumers {
    stopNode2()
    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(TestServiceActor.Evt.IdleStream)
    }
    sendToServiceOnNode1("consumer1", PoisonPill)
    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(TestServiceActor.Evt.IdleStream)
    }
    sendToServiceOnNode1("consumer2", PoisonPill)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.IdleStream)
  }

  it should "reopen stream when new consumer arrives" in new ServiceWith4ActiveConsumers {
    stopNode2()
    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(TestServiceActor.Evt.IdleStream)
    }
    sendToServiceOnNode1("consumer1", PoisonPill)
    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(TestServiceActor.Evt.IdleStream)
    }

    sendToServiceOnNode1("test", PublishString("string", "latest"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> "latest")


    sendToServiceOnNode1("consumer2", Close("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.IdleStream)

    clearEvents()

    sendToServiceOnNode1("consumer2", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> "latest")
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

  }


  trait With2Consumers1Service extends With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])
    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer1")
    onNode1ExpectExactlyOneEvent(ServicesBootstrapEvents.StartingService, 'service -> "consumer2")
    clearEvents()
  }

  it should "receive a signal" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", SendSignal("test", "signal"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.SignalResponseReceivedAckOk, 'path -> "/user/node/consumer1")
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.SignalResponseReceivedAckOk)
  }
  it should "receive a signal with payload" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", SendSignal("test", "signal", payload = "payload"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.SignalResponseReceivedAckOk, 'payload -> "Some(payload1)", 'path -> "/user/node/consumer1")
  }
  it should "receive a signal with payload and correlationId" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", SendSignal("test", "signal", payload = "payload", correlationId = Some("correlationId")))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.SignalResponseReceivedAckOk, 'payload -> "Some(payload1)", 'correlationId -> "Some(correlationId)", 'path -> "/user/node/consumer1")
  }
  it should "receive a signal with payload and correlationId, ordered group" in new With2Consumers1Service {
    for (i <- 1 to 100) sendToServiceOnNode1("consumer1", SendSignal("test", "signal", payload = s"payload$i:", correlationId = Some(s"correlation$i"), orderingGroup = Some("grp")))
    for (i <- 1 to 100) onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.SignalResponseReceivedAckOk, 'payload -> s"Some(payload$i:$i)", 'correlationId -> s"Some(correlation$i)", 'path -> "/user/node/consumer1")
  }
  it should "receive a signal and respond with a failure" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", SendSignal("test", "signal_failure", payload = "payload"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.SignalResponseReceivedAckFailed, 'payload -> "Some(failure)", 'path -> "/user/node/consumer1")
  }
  it should "receive a signal and do not respond" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", SendSignal("test", "signal_no_response", payload = "payload"))
    onNode1ExpectExactlyOneEvent(Evt.SignalReceived, 'subj -> "test|signal_no_response")
    duringPeriodInMillis(3000) {
      onNode1ExpectNoEvents(TestServiceConsumer.Evt.SignalResponseReceivedAckOk, 'path -> "/user/node/consumer1")
      onNode1ExpectNoEvents(TestServiceConsumer.Evt.SignalResponseReceivedAckFailed, 'path -> "/user/node/consumer1")
    }
  }

  it should "receive a signal and do not respond, client should timeout" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", SendSignal("test", "signal_no_response", payload = "payload", expiry = 2 seconds))
    onNode1ExpectExactlyOneEvent(Evt.SignalReceived, 'subj -> "test|signal_no_response")
    duringPeriodInMillis(3000) {
      onNode1ExpectNoEvents(TestServiceConsumer.Evt.SignalResponseReceivedAckOk, 'path -> "/user/node/consumer1")
      onNode1ExpectNoEvents(TestServiceConsumer.Evt.SignalResponseReceivedAckFailed, 'path -> "/user/node/consumer1")
    }
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.SignalTimeout, 'path -> "/user/node/consumer1")

  }







}