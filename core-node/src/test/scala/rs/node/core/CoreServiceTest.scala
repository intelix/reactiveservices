/*
 * Copyright 2014-16 Intelix Pty Ltd
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
package rs.node.core

import rs.core.SubjectTags.UserId
import rs.core.registry.ServiceRegistryActor
import rs.core.services.BaseServiceActor.StopRequest
import rs.core.services.{CompoundStreamId, ServiceEvt}
import rs.core.stream.ListStreamState.{ListSpecs, RejectAdd}
import rs.node.core.discovery.UdpClusterManagerActor
import rs.testkit
import rs.testkit._
import rs.testkit.components.TestServiceActor._
import rs.testkit.components.TestServiceConsumer.{Close, Open, SendSignal}
import rs.testkit.components._

import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

class CoreServiceTest extends StandardMultiNodeSpec {


  trait With4NodesAndTestOn1 extends With4Nodes {

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor])

    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+ ConfigFromContents("test.idle-stream-threshold=1s")

    expectFullyBuilt()
  }

  trait WithClusterAwareServiceOn1 extends WithNode1 {
    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+ ConfigFromContents("node.cluster.discovery.timeout=5s")

    override def node1Services: Map[String, Class[_]] = super.node1Services + ("test" -> classOf[testkit.components.ClusterAwareService])
  }

  trait WithClusterAwareServiceOn2 extends WithNode2 {
    override def node2Configs: Seq[ConfigReference] = super.node2Configs :+ ConfigFromContents("node.cluster.discovery.timeout=5s")

    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test" -> classOf[testkit.components.ClusterAwareService])
  }

  trait WithClusterAwareServiceOn3 extends WithNode3 {
    override def node3Configs: Seq[ConfigReference] = super.node3Configs :+ ConfigFromContents("node.cluster.discovery.timeout=5s")

    override def node3Services: Map[String, Class[_]] = super.node3Services + ("test" -> classOf[testkit.components.ClusterAwareService])
  }


  import testkit.components.ClusterAwareService._

  "Cluster-aware service on single node" should "receive MemberUp notification from own node" in new WithClusterAwareServiceOn1 {
    on node1 expectOne of EvtMemberUp + ('addr -> node1Address)
  }

  it should "become a leader" in new WithClusterAwareServiceOn1 {
    on node1 expectOne ofEach(
      EvtLeaderChanged + ('addr -> s"Some($node1Address)"),
      EvtLeaderTakeover)
  }

  "Cluster-aware service on two nodes" should "receive MemberUp notification from both nodes" in new WithClusterAwareServiceOn1 with WithClusterAwareServiceOn2 {
    on node1 expectOne ofEach(EvtMemberUp + ('addr -> node1Address), EvtMemberUp + ('addr -> node2Address))
    on node2 expectOne ofEach(EvtMemberUp + ('addr -> node1Address), EvtMemberUp + ('addr -> node2Address))
  }

  trait WithClusterAwareServiceRunningOnTwo extends WithClusterAwareServiceOn1 with WithClusterAwareServiceOn2 {
    on node1 expectOne ofEach(EvtMemberUp + ('addr -> node1Address), EvtMemberUp + ('addr -> node2Address))
    on node2 expectOne ofEach(EvtMemberUp + ('addr -> node1Address), EvtMemberUp + ('addr -> node2Address))
    clearEvents()
  }

  trait WithClusterAwareServiceRunningOnThree extends WithClusterAwareServiceRunningOnTwo with WithClusterAwareServiceOn3 {
    on node3 expectOne ofEach(EvtMemberUp + ('addr -> node1Address), EvtMemberUp + ('addr -> node2Address))
    clearEvents()
  }

  it should "receive EvtMemberUnreachable followed by EvtMemberRemoved when another node dies" in new WithClusterAwareServiceRunningOnTwo {
    stopNode1()
    on node2 expectSome of EvtMemberUnreachable + ('addr -> node1Address)
    on node2 expectOne of EvtMemberRemoved + ('addr -> node1Address)
  }

  it should "transfer leadership when one node dies" in new WithClusterAwareServiceRunningOnTwo {
    stopNode1()
    on node2 expectSome of EvtLeaderChanged + ('addr -> node2Address.r)
    on node2 expectOne of EvtLeaderTakeover
  }


  "Service configuration" should "be accessible from the service" in new With4NodesAndTestOn1 {
    override def node1Configs: Seq[ConfigReference] = super.node1Configs :+ ConfigFromContents("test.int-config-value=123")

    on node1 expectOne of TestServiceActor.EvtIntConfigValue + ('value -> 123)
  }


  "Service running on Node1" should "start and create remote endpoints" in new With4NodesAndTestOn1 {
    on node1 expectOne of ServiceEvt.EvtRemoteEndpointRegistered + TestServiceActor.EvtSourceId + ('location -> node1Address)
    on node1 expectOne of ServiceEvt.EvtRemoteEndpointRegistered + TestServiceActor.EvtSourceId + ('location -> node2Address)
    on node1 expectOne of ServiceEvt.EvtRemoteEndpointRegistered + TestServiceActor.EvtSourceId + ('location -> node3Address)
    on node1 expectOne of ServiceEvt.EvtRemoteEndpointRegistered + TestServiceActor.EvtSourceId + ('location -> node4Address)
  }

  it should "start and create remote endpoints on nodes joined later" in new With4NodesAndTestOn1 {
    on node1 expectOne ofEach(
      ServiceEvt.EvtRemoteEndpointRegistered + TestServiceActor.EvtSourceId + ('location -> node1Address),
      ServiceEvt.EvtRemoteEndpointRegistered + TestServiceActor.EvtSourceId + ('location -> node2Address),
      ServiceEvt.EvtRemoteEndpointRegistered + TestServiceActor.EvtSourceId + ('location -> node3Address),
      ServiceEvt.EvtRemoteEndpointRegistered + TestServiceActor.EvtSourceId + ('location -> node4Address)
      )
    clearEvents()
    new WithNode5 {
      on node1 expectOne of ServiceEvt.EvtRemoteEndpointRegistered + TestServiceActor.EvtSourceId + ('location -> node5Address)
    }
  }

  it should "register its remote endpoints with local registries on each node" in new With4NodesAndTestOn1 {
    on node1 expectOne of ServiceRegistryActor.EvtServiceRegistered + ('key -> "test")
    on node2 expectOne of ServiceRegistryActor.EvtServiceRegistered + ('key -> "test")
    on node3 expectOne of ServiceRegistryActor.EvtServiceRegistered + ('key -> "test")
    on node4 expectOne of ServiceRegistryActor.EvtServiceRegistered + ('key -> "test")
  }

  it should "not conflict with another service with the same name started on some other node" in new With4NodesAndTestOn1 {
    override def node2Services = super.node2Services ++ Map("test" -> classOf[TestServiceActor])

    on node1 expectOne of ServiceRegistryActor.EvtServiceRegistered +('key -> "test", 'locations -> 2)
    on node2 expectOne of ServiceRegistryActor.EvtServiceRegistered +('key -> "test", 'locations -> 2)
    on node3 expectOne of ServiceRegistryActor.EvtServiceRegistered +('key -> "test", 'locations -> 2)
    on node4 expectOne of ServiceRegistryActor.EvtServiceRegistered +('key -> "test", 'locations -> 2)

    on node1 expectOne of ServiceRegistryActor.EvtPublishingNewServiceLocation + ('key -> "test")
    on node2 expectOne of ServiceRegistryActor.EvtPublishingNewServiceLocation + ('key -> "test")
    on node3 expectOne of ServiceRegistryActor.EvtPublishingNewServiceLocation + ('key -> "test")
    on node4 expectOne of ServiceRegistryActor.EvtPublishingNewServiceLocation + ('key -> "test")

  }

  it should "remove remote endpoints and unregister location from local registries upon termination" in new With4NodesAndTestOn1 {

    on node1 expectOne of ServiceRegistryActor.EvtServiceRegistered + ('key -> "test")
    on node2 expectOne of ServiceRegistryActor.EvtServiceRegistered + ('key -> "test")
    on node3 expectOne of ServiceRegistryActor.EvtServiceRegistered + ('key -> "test")
    on node4 expectOne of ServiceRegistryActor.EvtServiceRegistered + ('key -> "test")

    serviceOnNode1("test") ! StopRequest

    on node1 expectOne of ServiceRegistryActor.EvtServiceUnregistered +('key -> "test", 'remainingLocations -> 0)
    on node2 expectOne of ServiceRegistryActor.EvtServiceUnregistered +('key -> "test", 'remainingLocations -> 0)
    on node3 expectOne of ServiceRegistryActor.EvtServiceUnregistered +('key -> "test", 'remainingLocations -> 0)
    on node4 expectOne of ServiceRegistryActor.EvtServiceUnregistered +('key -> "test", 'remainingLocations -> 0)
  }

  it should "be able to register for other service location updates, and get update when service become available" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test1" -> classOf[TestServiceActor])

    on node1 expectOne of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "Some".r, 'service -> "test1")
  }

  it should "be able to register for other service location updates, and get update when service become unavailable" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test1" -> classOf[TestServiceActor])

    on node1 expectOne of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "Some".r, 'service -> "test1")
    clearEvents()
    stopNode2()
    on node1 expectOne of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "None", 'service -> "test1")
  }

  it should "not receive other service location updates if previously selected location is still active, even when new locations become available" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test1" -> classOf[TestServiceActor])

    on node1 expectOne of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "Some".r, 'service -> "test1")
    clearEvents()
    new WithNode5 {
      override def node5Services: Map[String, Class[_]] = super.node5Services + ("test1" -> classOf[TestServiceActor])

      on node5 expectOne of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "Some".r, 'service -> "test1")
      within(3 seconds) {
        on node1 expectNone of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "Some".r, 'service -> "test1")
      }
    }
  }

  it should "receive other service location updates if previously selected location becomes unavailable and other service locations are available" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("test1" -> classOf[TestServiceActor])

    on node1 expectOne of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "Some".r, 'service -> "test1")
    clearEvents()
    new WithNode5 {
      override def node5Services: Map[String, Class[_]] = super.node5Services + ("test1" -> classOf[TestServiceActor])

      on node5 expectOne of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "Some".r, 'service -> "test1")
      within(3 seconds) {
        on node1 expectNone of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "Some".r, 'service -> "test1")
      }

      stopNode2()
      on node1 expectOne of TestServiceActor.EvtOtherServiceLocationChanged +('addr -> "Some".r, 'service -> "test1")
    }
  }



  "Service consumer" should "be able to open stream and receive an update when started on the same node" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services + ("consumer" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer")
    clearEvents()
    serviceOnNode1("consumer") ! Open("test", "string")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }
  it should "be able to open stream and receive an update when started on another node" in new With4NodesAndTestOn1 {
    override def node2Services: Map[String, Class[_]] = super.node2Services + ("consumer" -> classOf[TestServiceConsumer])

    on node2 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer")
    //    clearEvents()
    serviceOnNode2("consumer") ! Open("test", "string")
    on node2 expectOne of TestServiceConsumer.EvtStringUpdate +('sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }
  "Multiple service consumers" should "be able to open stream and receive an update when started on the same node" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer2")
    clearEvents()
    serviceOnNode1("consumer1") ! Open("test", "string")
    serviceOnNode1("consumer2") ! Open("test", "string")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }

  it should "be able to open stream and receive an update when started on different hosts" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    override def node2Services: Map[String, Class[_]] = super.node1Services +("consumer3" -> classOf[TestServiceConsumer], "consumer4" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer2")
    on node2 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer3")
    on node2 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer4")
    clearEvents()
    serviceOnNode1("consumer1") ! Open("test", "string")
    serviceOnNode1("consumer2") ! Open("test", "string")
    serviceOnNode2("consumer3") ! Open("test", "string")
    serviceOnNode2("consumer4") ! Open("test", "string")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    on node2 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    on node2 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer4", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }


  "Service" should "reject invalid subjects" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")

    serviceOnNode1("consumer1") ! Open("test", "invalid")

    on node1 expectOne of ServiceEvt.EvtSubjectMappingError + TestServiceActor.EvtSourceId

  }

  it should "not receive mapping request from remote endpoint if subject has already been mapped" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    serviceOnNode1("consumer1") ! Open("test", "string")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    on node1 expectOne of ServiceEvt.EvtSubjectMapped + TestServiceActor.EvtSourceId + ('subj -> "test|string")

    clearEvents()
    serviceOnNode1("consumer2") ! Open("test", "string")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    on node1 expectNone of ServiceEvt.EvtSubjectMapped + TestServiceActor.EvtSourceId + ('subj -> "test|string")
  }


  it should "map multiple subjects to a single stream" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    serviceOnNode1("consumer1") ! Open("test", "string")
    serviceOnNode1("consumer1") ! Open("test", "string1")
    serviceOnNode1("consumer2") ! Open("test", "string2")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string1", 'value -> TestServiceActor.AutoStringReply)
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string2", 'value -> TestServiceActor.AutoStringReply)

    clearEvents()

    serviceOnNode1("test") ! PublishString("string", "latest")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> "latest")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string1", 'value -> "latest")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string2", 'value -> "latest")

  }

  it should "close stream when all subjects mapped to the stream have been discarded" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    serviceOnNode1("consumer1") ! Open("test", "string")
    serviceOnNode1("consumer1") ! Open("test", "string1")
    serviceOnNode1("consumer2") ! Open("test", "string2")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string1", 'value -> TestServiceActor.AutoStringReply)
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string2", 'value -> TestServiceActor.AutoStringReply)

    clearEvents()

    serviceOnNode1("consumer1") ! Close("test", "string")
    serviceOnNode1("consumer1") ! Close("test", "string1")

    within(5 seconds) {
      on node1 expectNone of ServiceEvt.EvtIdleStream + TestServiceActor.EvtSourceId
    }
    clearEvents()

    serviceOnNode1("consumer2") ! Close("test", "string2")
    on node1 expectOne of ServiceEvt.EvtIdleStream + TestServiceActor.EvtSourceId

  }

  it should "support compound stream id" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    serviceOnNode1("consumer1") ! Open("test", "stringWithId", UserId(1))
    serviceOnNode1("consumer1") ! Open("test", "stringWithId", UserId(2))
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId(1), 'value -> TestServiceActor.AutoStringReply)
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId(2), 'value -> TestServiceActor.AutoStringReply)

    clearEvents()

    serviceOnNode1("test") ! PublishString(CompoundStreamId("string", 1), "latest")

    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId(1), 'value -> "latest")
    within(3 seconds) {
      on node1 expectNone of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId(2), 'value -> "latest")

    }

  }

  it should "notify when stream becomes active" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    serviceOnNode1("consumer1") ! Open("test", "stringWithId", UserId(1))

    on node1 expectOne of TestServiceActor.EvtStreamActive + ('stream -> "string#1")

  }

  it should "notify when stream becomes passive" in new With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    serviceOnNode1("consumer1") ! Open("test", "stringWithId", UserId(1))


    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "stringWithId", 'keys -> UserId(1), 'value -> TestServiceActor.AutoStringReply)
    clearEvents()

    serviceOnNode1("consumer1") ! Close("test", "stringWithId", UserId(1))

    on node1 expectOne of TestServiceActor.EvtStreamPassive + ('stream -> "string#1")
  }


  trait ServiceWith4ActiveConsumers extends With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    override def node2Services: Map[String, Class[_]] = super.node1Services +("consumer3" -> classOf[TestServiceConsumer], "consumer4" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer2")
    on node2 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer3")
    on node2 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer4")

    serviceOnNode1("consumer1") ! Open("test", "string")
    serviceOnNode1("consumer2") ! Open("test", "string")
    serviceOnNode2("consumer3") ! Open("test", "string")
    serviceOnNode2("consumer4") ! Open("test", "string")

    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer1", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    on node2 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    on node2 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer4", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    clearEvents()
  }

  it should "shut down stream at location when all consumers are gone from that location" in new ServiceWith4ActiveConsumers {
    serviceOnNode2("consumer3") ! StopRequest
    serviceOnNode2("consumer4") ! StopRequest
    on node1 expectOne of ServiceEvt.EvtStreamInterestRemoved + TestServiceActor.EvtSourceId + ('location -> node2Address)
  }

  it should "shut down stream when all consumers are gone" in new ServiceWith4ActiveConsumers {
    stopNode2()
    within(5 seconds) {
      on node1 expectNone of ServiceEvt.EvtIdleStream + TestServiceActor.EvtSourceId
    }
    serviceOnNode1("consumer1") ! StopRequest
    within(5 seconds) {
      on node1 expectNone of ServiceEvt.EvtIdleStream + TestServiceActor.EvtSourceId
    }
    serviceOnNode1("consumer2") ! StopRequest
    on node1 expectOne of ServiceEvt.EvtIdleStream + TestServiceActor.EvtSourceId
  }

  it should "reopen stream when new consumer arrives" in new ServiceWith4ActiveConsumers {
    stopNode2()
    within(5 seconds) {
      on node1 expectNone of ServiceEvt.EvtIdleStream + TestServiceActor.EvtSourceId
    }
    serviceOnNode1("consumer1") ! StopRequest
    within(5 seconds) {
      on node1 expectNone of ServiceEvt.EvtIdleStream + TestServiceActor.EvtSourceId
    }

    serviceOnNode1("test") ! PublishString("string", "latest")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> "latest")


    serviceOnNode1("consumer2") ! Close("test", "string")
    on node1 expectOne of ServiceEvt.EvtIdleStream + TestServiceActor.EvtSourceId

    clearEvents()

    serviceOnNode1("consumer2") ! Open("test", "string")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> "latest")
    on node1 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer2", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

  }


  trait With3Consumers1Service extends With4NodesAndTestOn1 {
    override def node1Services: Map[String, Class[_]] = super.node1Services +("consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    override def node3Services = super.node1Services + ("consumer3" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer2")
    on node3 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer3")
    clearEvents()
  }

  it should "receive a signal" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! SendSignal("test", "signal")
    on node3 expectOne of TestServiceConsumer.EvtSignalResponseReceivedAckOk + ('path -> "/user/node/consumer3")
    on node3 expectOne of TestServiceConsumer.EvtSignalResponseReceivedAckOk
  }
  it should "receive a signal with payload" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! SendSignal("test", "signal", payload = "payload")
    on node3 expectOne of TestServiceConsumer.EvtSignalResponseReceivedAckOk +('payload -> "Some(payload1)", 'path -> "/user/node/consumer3")
  }
  it should "receive a signal with payload and correlationId" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! SendSignal("test", "signal", payload = "payload", correlationId = Some("correlationId"))
    on node3 expectOne of TestServiceConsumer.EvtSignalResponseReceivedAckOk +('payload -> "Some(payload1)", 'correlationId -> "Some(correlationId)", 'path -> "/user/node/consumer3")
  }
  it should "receive a signal with payload and correlationId, ordered group" in new With3Consumers1Service {
    for (i <- 1 to 100) serviceOnNode3("consumer3") ! SendSignal("test", "signal", payload = s"payload$i:", correlationId = Some(s"correlation$i"), orderingGroup = Some("grp"))
    for (i <- 1 to 100) on node3 expectOne of TestServiceConsumer.EvtSignalResponseReceivedAckOk +('payload -> s"Some(payload$i:$i)", 'correlationId -> s"Some(correlation$i)", 'path -> "/user/node/consumer3")
  }
  it should "receive a signal and respond with a failure" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! SendSignal("test", "signal_failure", payload = "payload")
    on node3 expectOne of TestServiceConsumer.EvtSignalResponseReceivedAckFailed +('payload -> "Some(failure)", 'path -> "/user/node/consumer3")
  }
  it should "receive a signal and do not respond" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! SendSignal("test", "signal_no_response", payload = "payload")
    on node1 expectOne of TestServiceActor.EvtSignalReceived + ('subj -> "test|signal_no_response")
    within(3 seconds) {
      on node3 expectNone of TestServiceConsumer.EvtSignalResponseReceivedAckOk + ('path -> "/user/node/consumer3")
      on node3 expectNone of TestServiceConsumer.EvtSignalResponseReceivedAckFailed + ('path -> "/user/node/consumer3")
    }
  }

  it should "receive a signal and do not respond, client should timeout" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! SendSignal("test", "signal_no_response", payload = "payload", expiry = 2 seconds)
    on node1 expectOne of TestServiceActor.EvtSignalReceived + ('subj -> "test|signal_no_response")
    within(3 seconds) {
      on node3 expectNone of TestServiceConsumer.EvtSignalResponseReceivedAckOk + ('path -> "/user/node/consumer3")
      on node3 expectNone of TestServiceConsumer.EvtSignalResponseReceivedAckFailed + ('path -> "/user/node/consumer3")
    }
    on node3 expectOne of TestServiceConsumer.EvtSignalTimeout + ('path -> "/user/node/consumer3")

  }


  trait With2Consumers1ServiceAndGremlins extends WithGremlinOn4Nodes {
    override def node1Services = super.node1Services +("test" -> classOf[TestServiceActor], "consumer1" -> classOf[TestServiceConsumer], "consumer2" -> classOf[TestServiceConsumer])

    override def node3Services = super.node1Services + ("consumer3" -> classOf[TestServiceConsumer])

    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer1")
    on node1 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer2")
    on node3 expectOne of ClusterNodeActor.EvtStartingService + ('service -> "consumer3")

    expectFullyBuilt()
    clearEvents()
  }


  "Service consumer" should "receive updates posted during network split (when cluster was not partitioned)" in new WithGremlin with With2Consumers1ServiceAndGremlins {

    serviceOnNode3("consumer3") ! Open("test", "string")
    on node3 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    serviceOnNode1("test") ! PublishString("string", "update1")
    on node3 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update1")

    onNode1BlockNode(3, 4)
    onNode2BlockNode(3, 4)

    onNode3BlockNode(1, 2)
    onNode4BlockNode(1, 2)

    within(5 seconds) {
      on node1 expectNone of UdpClusterManagerActor.EvtNodeRemoved
      on node2 expectNone of UdpClusterManagerActor.EvtNodeRemoved
      on node3 expectNone of UdpClusterManagerActor.EvtNodeRemoved
      on node4 expectNone of UdpClusterManagerActor.EvtNodeRemoved
    }

    serviceOnNode1("test") ! PublishString("string", "update2")

    within(5 seconds) {}

    onNode1UnblockNode(3, 4)
    onNode2UnblockNode(3, 4)
    onNode3UnblockNode(1, 2)
    onNode4UnblockNode(1, 2)

    within(10 seconds) {
      on node1 expectNone of ClusterNodeActor.EvtClusterMergeTrigger
      on node2 expectNone of ClusterNodeActor.EvtClusterMergeTrigger
      on node3 expectNone of ClusterNodeActor.EvtClusterMergeTrigger
      on node4 expectNone of ClusterNodeActor.EvtClusterMergeTrigger
    }


    on node1 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node3Address)
    on node1 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node4Address)
    on node2 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node3Address)
    on node2 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node4Address)
    on node4 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node2Address)
    on node4 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node1Address)

    on node3 expectSome of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update2")


    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }



  it should "receve only the latest snapshot update if multiple updates been posted during network split" in new WithGremlin with With2Consumers1ServiceAndGremlins {

    serviceOnNode3("consumer3") ! Open("test", "string")
    on node3 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    serviceOnNode1("test") ! PublishString("string", "update1")
    on node3 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update1")

    onNode1BlockNode(3, 4)
    onNode2BlockNode(3, 4)

    onNode3BlockNode(1, 2)
    onNode4BlockNode(1, 2)

    within(5 seconds) {
      on node1 expectNone of UdpClusterManagerActor.EvtNodeRemoved
      on node2 expectNone of UdpClusterManagerActor.EvtNodeRemoved
      on node3 expectNone of UdpClusterManagerActor.EvtNodeRemoved
      on node4 expectNone of UdpClusterManagerActor.EvtNodeRemoved
    }

    serviceOnNode1("test") ! PublishString("string", "update2")
    serviceOnNode1("test") ! PublishString("string", "update3")
    serviceOnNode1("test") ! PublishString("string", "update4")
    serviceOnNode1("test") ! PublishString("string", "update5")

    within(5 seconds) {}


    onNode1UnblockNode(3, 4)
    onNode2UnblockNode(3, 4)
    onNode3UnblockNode(1, 2)
    onNode4UnblockNode(1, 2)

    within(10 seconds) {
      on node1 expectNone of ClusterNodeActor.EvtClusterMergeTrigger
      on node2 expectNone of ClusterNodeActor.EvtClusterMergeTrigger
      on node3 expectNone of ClusterNodeActor.EvtClusterMergeTrigger
      on node4 expectNone of ClusterNodeActor.EvtClusterMergeTrigger
    }


    on node1 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node3Address)
    on node1 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node4Address)
    on node2 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node3Address)
    on node2 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node4Address)
    on node4 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node2Address)
    on node4 expectSome of UdpClusterManagerActor.EvtNodeReachable + ('addr -> node1Address)

    on node3 expectSome of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update5")
    on node3 expectNone of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update2")
    on node3 expectNone of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update3")
    on node3 expectNone of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update4")


    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs ++ sensitiveConfigWithAutoDownOff
  }







  "String stream subscriber" should "receive an initial update when subscribed" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! Open("test", "string")
    on node3 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
  }

  it should "may or may not receive all updates, but must receive the very last one" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! Open("test", "string")
    on node3 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)

    serviceOnNode1("test") ! PublishString("string", "update1")
    serviceOnNode1("test") ! PublishString("string", "update2")
    serviceOnNode1("test") ! PublishString("string", "update3")
    on node3 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> "update3")

  }

  "Set stream subscriber" should "receive an initial update when subscribed, followed by all consequent updates" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! Open("test", "set")
    on node3 expectSome of TestServiceConsumer.EvtSetUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "set", 'value -> TestServiceActor.AutoSetReply.mkString(","))
    clearEvents()

    serviceOnNode1("test") ! PublishSet("set", Set("c", "d"))
    on node3 expectSome of TestServiceConsumer.EvtSetUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "set", 'value -> "c,d")
    clearEvents()

    serviceOnNode1("test") ! PublishSetAdd("set", Set("c", "x"))
    on node3 expectSome of TestServiceConsumer.EvtSetUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "set", 'value -> "c,d,x")
    clearEvents()

    serviceOnNode1("test") ! PublishSetRemove("set", Set("c", "a"))
    on node3 expectSome of TestServiceConsumer.EvtSetUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "set", 'value -> "d,x")

  }

  it should "not conflict with string subscription" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! Open("test", "set")
    on node3 expectSome of TestServiceConsumer.EvtSetUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "set", 'value -> TestServiceActor.AutoSetReply.mkString(","))
    clearEvents()

    serviceOnNode3("consumer3") ! Open("test", "string")
    on node3 expectOne of TestServiceConsumer.EvtStringUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "string", 'value -> TestServiceActor.AutoStringReply)
    clearEvents()

    serviceOnNode1("test") ! PublishSet("set", Set("c", "d"))
    on node3 expectSome of TestServiceConsumer.EvtSetUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "set", 'value -> "c,d")

    on node3 expectNone of TestServiceConsumer.EvtStringUpdate

  }

  "Map stream subscriber" should "receive an initial update when subscribed, followed by all consequent updates" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! Open("test", "map")
    on node3 expectSome of TestServiceConsumer.EvtMapUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "map", 'value -> "Map(s -> a, i -> 1, b -> true)")
    clearEvents()

    serviceOnNode1("test") ! PublishMap("map", Array("c", 123, false))
    on node3 expectSome of TestServiceConsumer.EvtMapUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "map", 'value -> "Map(s -> c, i -> 123, b -> false)")
    clearEvents()

    serviceOnNode1("test") ! PublishMap("map", Array("c", 123, true))
    on node3 expectSome of TestServiceConsumer.EvtMapUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "map", 'value -> "Map(s -> c, i -> 123, b -> true)")
    clearEvents()

    serviceOnNode1("test") ! PublishMapAdd("map", "s" -> "bla")
    on node3 expectSome of TestServiceConsumer.EvtMapUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "map", 'value -> "Map(s -> bla, i -> 123, b -> true)")
    clearEvents()

  }

  "List stream subscriber" should "receive an initial update when subscribed, followed by all consequent updates" in new With3Consumers1Service {
    serviceOnNode3("consumer3") ! Open("test", "list1") // reject add when reached 5 items
    serviceOnNode3("consumer3") ! Open("test", "list2") // remove from head when reached 5 items
    serviceOnNode3("consumer3") ! Open("test", "list3") // remove from tail when reached 5 items
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list1", 'value -> "1,2,3,4")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2", 'value -> "1,2,3,4")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list3", 'value -> "1,2,3,4")
    clearEvents()

    serviceOnNode1("test") ! PublishList("list1", List("a", "b"), ListSpecs(5, RejectAdd))
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list1", 'value -> "a,b")
    clearEvents()

    serviceOnNode1("test") ! PublishListAdd("list1", -1, "5")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list1", 'value -> "a,b,5")
    clearEvents()

    serviceOnNode1("test") ! PublishListAdd("list1", 0, "6")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list1", 'value -> "6,a,b,5")
    clearEvents()

    serviceOnNode1("test") ! PublishListAdd("list1", 2, "7")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list1", 'value -> "6,a,7,b,5")
    clearEvents()

    serviceOnNode1("test") ! PublishListAdd("list1", 2, "8")
    serviceOnNode1("test") ! PublishListAdd("list1", 0, "8")
    serviceOnNode1("test") ! PublishListAdd("list1", -1, "8")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list1", 'value -> "6,a,7,b,5")
    clearEvents()

    serviceOnNode1("test") ! PublishList("list1", List("a", "b", "c", "d", "e"), ListSpecs(6, RejectAdd))
    serviceOnNode1("test") ! PublishListAdd("list1", 2, "8")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list1", 'value -> "a,b,8,c,d,e")
    clearEvents()


    serviceOnNode1("test") ! PublishListAdd("list2", -1, "5")
    serviceOnNode1("test") ! PublishListAdd("list3", -1, "5")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2", 'value -> "1,2,3,4,5")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list3", 'value -> "1,2,3,4,5")
    clearEvents()

    serviceOnNode1("test") ! PublishListAdd("list2", 0, "6")
    serviceOnNode1("test") ! PublishListAdd("list3", 0, "6")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2", 'value -> "1,2,3,4,5")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list3", 'value -> "6,1,2,3,4")
    clearEvents()

    serviceOnNode1("test") ! PublishListAdd("list2", 2, "7")
    serviceOnNode1("test") ! PublishListAdd("list3", 2, "7")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,7,3,4,5")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list3", 'value -> "6,1,7,2,3")
    clearEvents()

    serviceOnNode1("test") ! PublishListRemove("list2", 2)
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,7,4,5")
    clearEvents()

    serviceOnNode1("test") ! PublishListReplace("list2", -2, "x")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,7,x,5")
    clearEvents()

    serviceOnNode1("test") ! PublishListFindReplace("list2", "7", "x")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,x,x,5")
    clearEvents()

    serviceOnNode1("test") ! PublishListFindReplace("list2", "x", "a")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,a,x,5")
    clearEvents()

    serviceOnNode1("test") ! PublishListFindRemove("list2", "z")
    within(1 seconds) {
      on node3 expectNone of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2")
    }
    clearEvents()

    serviceOnNode1("test") ! PublishListFindRemove("list2", "a")
    on node3 expectSome of TestServiceConsumer.EvtListUpdate +('path -> "/user/node/consumer3", 'sourceService -> "test", 'topic -> "list2", 'value -> "2,x,5")
    clearEvents()

  }


}
