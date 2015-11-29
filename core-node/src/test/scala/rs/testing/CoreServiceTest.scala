/*
 * Copyright 2014-15 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rs.testing

import org.scalatest.FlatSpec
import rs.core.SubjectKeys.UserId
import rs.core.registry.ServiceRegistrySysevents
import rs.core.services.BaseServiceCell.StopRequest
import rs.core.services.StreamId
import rs.core.stream.ListStreamState.{ListSpecs, RejectAdd}
import rs.core.sysevents.Sysevent
import rs.node.core.ServiceNodeActor
import rs.node.core.discovery.UdpClusterManagerActor
import rs.testing.components.TestServiceActor._
import rs.testing.components.TestServiceConsumer.{Close, Open, SendSignal}
import rs.testing.components.{ClusterAwareService, TestServiceActor, TestServiceConsumer}

import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

class CoreServiceTest extends FlatSpec with ManagedNodeTestContext with IsolatedActorSystems {


  trait With4NodesAndTestOn1 extends With4Nodes {

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor])
  }

  trait WithClusterAwareServiceOn1 extends WithNode1 {
    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node1Services: Map[String, Class[_]] = super.node1Services + ("test" -> classOf[ClusterAwareService])
  }

  trait WithClusterAwareServiceOn2 extends WithNode2 {
    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test" -> classOf[ClusterAwareService])
  }

  trait WithClusterAwareServiceOn3 extends WithNode3 {
    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node3Services: Map[String, Class[_]] = super.node3Services + ("test" -> classOf[ClusterAwareService])
  }





  "Cluster-aware service on single node" should "receive MemberUp notification from own node" in new WithClusterAwareServiceOn1 {

    import ClusterAwareService.Evt._

    MemberUp matching 'addr -> node1Address expectedOn node2 within (1 second)


    //this exp 1 of ClusterAwareService.Evt.MemberUp




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

    sendToServiceOnNode1("test", StopRequest)

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

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer")
    clearEvents()
    sendToServiceOnNode1("consumer", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }
  it should "be able to open stream and receive an update when started on another node" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("consumer" -> classOf[TestServiceConsumer])

    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer")
    //    clearEvents()
    sendToServiceOnNode2("consumer", Open("test", "string"))
    onNode2ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }
  "Multiple service consumers" should "be able to open stream and receive an update when started on the same node" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer2")
    clearEvents()
    sendToServiceOnNode1("consumer1", Open("test", "string"))
    sendToServiceOnNode1("consumer2", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }

  it should "be able to open stream and receive an update when started on different hosts" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    override def node2Services: Map[String, Class[_]] = super.node1Services +("consumer3" -> classOf[TestServiceConsumer], "consumer4" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer2")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer3")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer4")
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

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")

    sendToServiceOnNode1("consumer1", Open("test", "invalid"))

    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.SubjectMappingError)

  }

  it should "not receive mapping request from remote endpoint if subject has already been mapped" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
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

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
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

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
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

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
    sendToServiceOnNode1("consumer1", Open("test", "stringWithId", UserId("id1")))
    sendToServiceOnNode1("consumer1", Open("test", "stringWithId", UserId("id2")))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id1"), 'value -> TestServiceActor.AutoStringReply)
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id2"), 'value -> TestServiceActor.AutoStringReply)

    clearEvents()

    sendToServiceOnNode1("test", PublishString(StreamId("string", Some("id1")), "latest"))

    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id1"), 'value -> "latest")
    duringPeriodInMillis(3000) {
      onNode1ExpectNoEvents(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id2"), 'value -> "latest")

    }

  }

  it should "notify when stream becomes active" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
    sendToServiceOnNode1("consumer1", Open("test", "stringWithId", UserId("id1")))

    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.StreamActive, 'stream -> "string#id1")

  }

  it should "notify when stream becomes passive" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
    sendToServiceOnNode1("consumer1", Open("test", "stringWithId", UserId("id1")))


    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId("id1"), 'value -> TestServiceActor.AutoStringReply)
    clearEvents()

    sendToServiceOnNode1("consumer1", Close("test", "stringWithId", UserId("id1")))

    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.StreamPassive, 'stream -> "string#id1")
  }


  trait ServiceWith4ActiveConsumers extends With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    override def node2Services: Map[String, Class[_]] = super.node1Services +("consumer3" -> classOf[TestServiceConsumer], "consumer4" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer2")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer3")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer4")

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
    sendToServiceOnNode2("consumer3", StopRequest)
    sendToServiceOnNode2("consumer4", StopRequest)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.StreamInterestRemoved, 'location -> node2Address)
    printRaisedEvents()
  }

  it should "shut down stream when all consumers are gone" in new ServiceWith4ActiveConsumers {
    stopNode2()
    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(TestServiceActor.Evt.IdleStream)
    }
    sendToServiceOnNode1("consumer1", StopRequest)
    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(TestServiceActor.Evt.IdleStream)
    }
    sendToServiceOnNode1("consumer2", StopRequest)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.IdleStream)
  }

  it should "reopen stream when new consumer arrives" in new ServiceWith4ActiveConsumers {
    stopNode2()
    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(TestServiceActor.Evt.IdleStream)
    }
    sendToServiceOnNode1("consumer1", StopRequest)
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

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer2")
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


  trait With2Consumers1ServiceAndGremlins extends WithGremlinOn4Nodes {
    override def node1Services = super.node1Services +("test" -> classOf[TestServiceActor], "consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    override def node3Services = super.node1Services + ("consumer3" -> classOf[TestServiceConsumer])

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer1")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer2")
    onNode3ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "consumer3")
  }


  "Service consumer" should "receive updates posted during network split (when cluster was not partitioned)" in new WithGremlin with With2Consumers1ServiceAndGremlins {

    expectFullyBuilt()
    clearEvents()

    sendToServiceOnNode3("consumer3", Open("test", "string"))
    onNode3ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode3ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update1")

    atNode1BlockNode(3, 4)
    atNode2BlockNode(3, 4)

    atNode3BlockNode(1, 2)
    atNode4BlockNode(1, 2)

    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode2ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode3ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode4ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
    }

    sendToServiceOnNode1("test", PublishString("string", "update2"))

    duringPeriodInMillis(5000) {}

    atNode1UnblockNode(3, 4)
    atNode2UnblockNode(3, 4)
    atNode3UnblockNode(1, 2)
    atNode4UnblockNode(1, 2)

    duringPeriodInMillis(10000) {
      onNode1ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)
      onNode2ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)
      onNode3ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)
      onNode4ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)
    }


    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node4Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node3Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node2Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node1Address)

    onNode3ExpectOneOrMoreEvents(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update2")


    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }



  it should "receve only the latest snapshot update if multiple updates been posted during network split" in new WithGremlin with With2Consumers1ServiceAndGremlins {

    expectFullyBuilt()
    clearEvents()

    sendToServiceOnNode3("consumer3", Open("test", "string"))
    onNode3ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode3ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update1")

    atNode1BlockNode(3, 4)
    atNode2BlockNode(3, 4)

    atNode3BlockNode(1, 2)
    atNode4BlockNode(1, 2)

    duringPeriodInMillis(5000) {
      onNode1ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode2ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode3ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
      onNode4ExpectNoEvents(UdpClusterManagerActor.Evt.NodeRemoved)
    }

    sendToServiceOnNode1("test", PublishString("string", "update2"))
    sendToServiceOnNode1("test", PublishString("string", "update3"))
    sendToServiceOnNode1("test", PublishString("string", "update4"))
    sendToServiceOnNode1("test", PublishString("string", "update5"))

    duringPeriodInMillis(5000) {}


    atNode1UnblockNode(3, 4)
    atNode2UnblockNode(3, 4)
    atNode3UnblockNode(1, 2)
    atNode4UnblockNode(1, 2)

    duringPeriodInMillis(10000) {
      onNode1ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)
      onNode2ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)
      onNode3ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)
      onNode4ExpectNoEvents(ServiceNodeActor.Evt.ClusterMergeTrigger)
    }


    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node3Address)
    onNode1ExpectSomeEventsWithTimeout(15000, UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node4Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node3Address)
    onNode2ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node4Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node2Address)
    onNode4ExpectOneOrMoreEvents(UdpClusterManagerActor.Evt.NodeReachable, 'addr -> node1Address)

    onNode3ExpectOneOrMoreEvents(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update5")
    onNode3ExpectNoEvents(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update2")
    onNode3ExpectNoEvents(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update3")
    onNode3ExpectNoEvents(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update4")


    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }







  "String stream subscriber" should "receive an initial update when subscribed" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }

  it should "may or may not receive all updates, but must receive the very last one" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    sendToServiceOnNode1("test", PublishString("string", "update2"))
    sendToServiceOnNode1("test", PublishString("string", "update3"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> "update3")

  }

  "Set stream subscriber" should "receive an initial update when subscribed, followed by all consequent updates" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", Open("test", "set"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.SetUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "set", 'value -> TestServiceActor.AutoSetReply.mkString(","))
    clearEvents()

    sendToServiceOnNode1("test", PublishSet("set", Set("c", "d")))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.SetUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "set", 'value -> "c,d")
    clearEvents()

    sendToServiceOnNode1("test", PublishSetAdd("set", Set("c", "x")))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.SetUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "set", 'value -> "c,d,x")
    clearEvents()

    sendToServiceOnNode1("test", PublishSetRemove("set", Set("c", "a")))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.SetUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "set", 'value -> "d,x")

  }

  it should "not conflict with string subscription" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", Open("test", "set"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.SetUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "set", 'value -> TestServiceActor.AutoSetReply.mkString(","))
    clearEvents()

    sendToServiceOnNode1("consumer1", Open("test", "string"))
    onNode1ExpectExactlyOneEvent(TestServiceConsumer.Evt.StringUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    clearEvents()

    sendToServiceOnNode1("test", PublishSet("set", Set("c", "d")))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.SetUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "set", 'value -> "c,d")

    onNode1ExpectNoEvents(TestServiceConsumer.Evt.StringUpdate)

  }

  "Map stream subscriber" should "receive an initial update when subscribed, followed by all consequent updates" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", Open("test", "map"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.MapUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "map", 'value -> "Map(s -> a, i -> 1, b -> true)")
    clearEvents()

    sendToServiceOnNode1("test", PublishMap("map", Array("c", 123, false)))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.MapUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "map", 'value -> "Map(s -> c, i -> 123, b -> false)")
    clearEvents()

    sendToServiceOnNode1("test", PublishMap("map", Array("c", 123, true)))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.MapUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "map", 'value -> "Map(s -> c, i -> 123, b -> true)")
    clearEvents()

    sendToServiceOnNode1("test", PublishMapAdd("map", "s" -> "bla"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.MapUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "map", 'value -> "Map(s -> bla, i -> 123, b -> true)")
    clearEvents()

  }

  "List stream subscriber" should "receive an initial update when subscribed, followed by all consequent updates" in new With2Consumers1Service {
    sendToServiceOnNode1("consumer1", Open("test", "list1")) // reject add when reached 5 items
    sendToServiceOnNode1("consumer1", Open("test", "list2")) // remove from head when reached 5 items
    sendToServiceOnNode1("consumer1", Open("test", "list3")) // remove from tail when reached 5 items
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list1", 'value -> "1,2,3,4")
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2", 'value -> "1,2,3,4")
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list3", 'value -> "1,2,3,4")
    clearEvents()

    sendToServiceOnNode1("test", PublishList("list1", List("a", "b"), ListSpecs(5, RejectAdd)))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list1", 'value -> "a,b")
    clearEvents()

    sendToServiceOnNode1("test", PublishListAdd("list1", -1, "5"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list1", 'value -> "a,b,5")
    clearEvents()

    sendToServiceOnNode1("test", PublishListAdd("list1", 0, "6"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list1", 'value -> "6,a,b,5")
    clearEvents()

    sendToServiceOnNode1("test", PublishListAdd("list1", 2, "7"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list1", 'value -> "6,a,7,b,5")
    clearEvents()

    sendToServiceOnNode1("test", PublishListAdd("list1", 2, "8"))
    sendToServiceOnNode1("test", PublishListAdd("list1", 0, "8"))
    sendToServiceOnNode1("test", PublishListAdd("list1", -1, "8"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list1", 'value -> "6,a,7,b,5")
    clearEvents()

    sendToServiceOnNode1("test", PublishList("list1", List("a", "b", "c", "d", "e"), ListSpecs(6, RejectAdd)))
    sendToServiceOnNode1("test", PublishListAdd("list1", 2, "8"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list1", 'value -> "a,b,8,c,d,e")
    clearEvents()


    sendToServiceOnNode1("test", PublishListAdd("list2", -1, "5"))
    sendToServiceOnNode1("test", PublishListAdd("list3", -1, "5"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2", 'value -> "1,2,3,4,5")
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list3", 'value -> "1,2,3,4,5")
    clearEvents()

    sendToServiceOnNode1("test", PublishListAdd("list2", 0, "6"))
    sendToServiceOnNode1("test", PublishListAdd("list3", 0, "6"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2", 'value -> "1,2,3,4,5")
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list3", 'value -> "6,1,2,3,4")
    clearEvents()

    sendToServiceOnNode1("test", PublishListAdd("list2", 2, "7"))
    sendToServiceOnNode1("test", PublishListAdd("list3", 2, "7"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,7,3,4,5")
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list3", 'value -> "6,1,7,2,3")
    clearEvents()

    sendToServiceOnNode1("test", PublishListRemove("list2", 2))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,7,4,5")
    clearEvents()

    sendToServiceOnNode1("test", PublishListReplace("list2", -2, "x"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,7,x,5")
    clearEvents()

    sendToServiceOnNode1("test", PublishListFindReplace("list2", "7", "x"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,x,x,5")
    clearEvents()

    sendToServiceOnNode1("test", PublishListFindReplace("list2", "x", "a"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,a,x,5")
    clearEvents()

    sendToServiceOnNode1("test", PublishListFindRemove("list2", "z"))
    duringPeriodInMillis(1000) {
      onNode1ExpectNoEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2")
    }
    clearEvents()

    sendToServiceOnNode1("test", PublishListFindRemove("list2", "a"))
    onNode1ExpectOneOrMoreEvents(TestServiceConsumer.Evt.ListUpdate, 'path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,x,5")
    clearEvents()

  }


}
