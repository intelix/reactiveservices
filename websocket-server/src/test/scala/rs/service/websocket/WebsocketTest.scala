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
package rs.service.websocket

import org.scalatest.FlatSpec
import play.api.libs.json.Json
import rs.core.Subject
import rs.core.services.BaseServiceCell.StopRequest
import rs.core.services.StreamId
import rs.node.core.ServiceNodeActor
import rs.service.auth.UserAuthenticationSysevents
import rs.service.websocket.WebsocketClientStubService._
import rs.testing._
import rs.testing.components.TestServiceActor
import rs.testing.components.TestServiceActor._

class WebsocketTest extends FlatSpec with ManagedNodeTestContext with IsolatedActorSystems {


  trait With4NodesAndTestOn1 extends With4Nodes {

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor], "client" -> classOf[WebsocketClientStubService])

    override def node2Services = super.node2Services ++ Map("test2" -> classOf[TestServiceActor])

    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromFile("websocket-server-defaults"),
        ConfigFromContents(
          """
            |websocket-server.ping.enabled=off
            |websocket-server.partials.enabled=off
            |websocket-server.auth.enabled=off
            |websocket-server.aggregator.enabled=off
          """.stripMargin)
      )

    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "client")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "websocket-server")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test2")
    clearEvents()

  }

  "Websocket client" should "successfully connect to the endpoint and upgrade to websocket" in new With4NodesAndTestOn1 {
    sendToServiceOnNode1("client", StartWebsocketClient("c1", "localhost", 8080))
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded)
  }

  trait WithClientConnected extends With4NodesAndTestOn1 {
    sendToServiceOnNode1("client", StartWebsocketClient("c1", "localhost", 8080))
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded)
    clearEvents()
  }

  it should "subscribe to a stream" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
  }

  it should "receive updates when service posts them" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    clearEvents()

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update1")
  }

  it should "receive updates only for the subscribed streams" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    clearEvents()

    sendToServiceOnNode1("test", PublishString("string1", "update1"))
    sendToServiceOnNode1("test", PublishMap("map", Array("a", 123, false)))
    duringPeriodInMillis(3000) {
      onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate)
      onNode1ExpectNoEvents(WebsocketClientStubService.Evt.MapUpdate)
    }
  }

  it should "receive notification when service becomes unavailable" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    clearEvents()

    sendToServiceOnNode1("test", StopRequest)
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedServiceNotAvailable)
  }

  it should "receive notification when node running the service becomes unavailable" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test2", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    clearEvents()

    stopNode2()
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedServiceNotAvailable)
  }

  it should "receive notification when service becomes available" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test3", "string")))

    duringPeriodInMillis(2000) {
      onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test3", 'topic -> "string", 'id -> "c1", 'value -> "hello")
    }
    clearEvents()

    new WithNode5 {
      override def node5Services: Map[String, Class[_]] = super.node5Services ++ Map("test3" -> classOf[TestServiceActor])

      onNode5ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test3")
      onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test3", 'topic -> "string", 'id -> "c1", 'value -> "hello")
    }

  }


  it should "receive notification when service becomes unavailable and then receive an update when service becomes available again" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test2", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test2", 'topic -> "string", 'id -> "c1", 'value -> "hello")
    sendToServiceOnNode2("test2", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test2", 'topic -> "string", 'id -> "c1", 'value -> "update1")
    clearEvents()

    stopNode2()
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedServiceNotAvailable)


    new WithNode5 {
      override def node5Services: Map[String, Class[_]] = super.node5Services ++ Map("test2" -> classOf[TestServiceActor])

      onNode5ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test2")
      onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test2", 'topic -> "string", 'id -> "c1", 'value -> "hello")
    }

  }

  it should "be able to subscribe to multiple streams and receive corresponding updates" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string1")))
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "stringX")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string1", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "stringX", 'id -> "c1", 'value -> "helloX")
    clearEvents()

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update1")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string1", 'id -> "c1", 'value -> "update1")

    sendToServiceOnNode1("test", PublishString("stringX", "update1X"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "stringX", 'id -> "c1", 'value -> "update1X")
  }

  it should "be able to drop subscription and no longer receive updates" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    clearEvents()

    sendToServiceOnNode1("client/c1", CloseSubscriptionFromStub(Subject("test", "string")))
    duringPeriodInMillis(2000) {
      onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate)
    }
    sendToServiceOnNode1("test", PublishString("string", "update1"))
    duringPeriodInMillis(2000) {
      onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate)
    }

    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.IdleStream, 'stream -> "string")

  }

  it should "be able to drop subscription and this should not affect any other active streams" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string1")))
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "stringX")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string1", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "stringX", 'id -> "c1", 'value -> "helloX")
    clearEvents()

    sendToServiceOnNode1("client/c1", CloseSubscriptionFromStub(Subject("test", "string")))
    duringPeriodInMillis(2000) {
      onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate)
    }
    sendToServiceOnNode1("test", PublishString("string", "update1"))
    sendToServiceOnNode1("test", PublishString("stringX", "updateX"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string1", 'id -> "c1", 'value -> "update1")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "stringX", 'id -> "c1", 'value -> "updateX")
    duringPeriodInMillis(2000) {
      onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1")
    }

  }

  it should "receive updates for String stream" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update1")
  }

  it should "receive updates for Map stream" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "map")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.MapUpdate, 'topic -> "map", 'id -> "c1", 'value -> "Map(s -> a, i -> 1, b -> true)")
    sendToServiceOnNode1("test", PublishMapAdd("map", "s" -> "b"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.MapUpdate, 'topic -> "map", 'id -> "c1", 'value -> "Map(s -> b, i -> 1, b -> true)")
  }

  it should "receive updates for Set stream" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "set")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.SetUpdate, 'topic -> "set", 'id -> "c1", 'value -> "a,b")
    sendToServiceOnNode1("test", PublishSetAdd("set", Set("x", "y")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.SetUpdate, 'topic -> "set", 'id -> "c1", 'value -> "a,b,x,y")
  }

  it should "receive updates for List stream" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "list1")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ListUpdate, 'topic -> "list1", 'id -> "c1", 'value -> "1,2,3,4")
    sendToServiceOnNode1("test", PublishListAdd("list1", 0, "x"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ListUpdate, 'topic -> "list1", 'id -> "c1", 'value -> "x,1,2,3,4")
  }

  it should "receive partial Map updates when enabled" in new WithClientConnected {

    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromContents(
          """
            |websocket-server.partials.enabled=on
          """.stripMargin)
      )


    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "map")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.MapUpdate, 'topic -> "map", 'id -> "c1", 'value -> "Map(s -> a, i -> 1, b -> true)")
    clearEvents()

    sendToServiceOnNode1("test", PublishMapAdd("map", "s" -> "b"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.MapUpdate, 'topic -> "map", 'id -> "c1", 'value -> "Map(s -> b, i -> 1, b -> true)")

    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ReceivedStreamStateTransitionUpdate, 'transition -> "Partial.+b,NoChange,NoChange".r)

  }

  it should "receive partial List updates when enabled" in new WithClientConnected {

    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromContents(
          """
            |websocket-server.partials.enabled=on
          """.stripMargin)
      )


    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "list1")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ListUpdate, 'topic -> "list1", 'id -> "c1", 'value -> "1,2,3,4")
    clearEvents()

    sendToServiceOnNode1("test", PublishListAdd("list1", 0, "x"))
    sendToServiceOnNode1("test", PublishListReplace("list1", 0, "y"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ListUpdate, 'topic -> "list1", 'id -> "c1", 'value -> "x,1,2,3,4")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ListUpdate, 'topic -> "list1", 'id -> "c1", 'value -> "y,1,2,3,4")

    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedStreamStateTransitionUpdate, 'transition -> "Partial.+Replace[(]0,y[)]".r)

  }

  it should "receive partial Set updates when enabled" in new WithClientConnected {

    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromContents(
          """
            |websocket-server.partials.enabled=on
          """.stripMargin)
      )

    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "set")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.SetUpdate, 'topic -> "set", 'id -> "c1", 'value -> "a,b")
    clearEvents()
    sendToServiceOnNode1("test", PublishSetAdd("set", Set("x", "y")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.SetUpdate, 'topic -> "set", 'id -> "c1", 'value -> "a,b,x,y")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedStreamStateTransitionUpdate, 'transition -> "Partial.+Add[(]y[)]".r)

  }


  it should "be able to reset the stream" in new WithClientConnected {

    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromContents(
          """
            |websocket-server.partials.enabled=on
          """.stripMargin)
      )


    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "map")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.MapUpdate, 'topic -> "map", 'id -> "c1", 'value -> "Map(s -> a, i -> 1, b -> true)")
    clearEvents()

    sendToServiceOnNode1("test", PublishMapAdd("map", "s" -> "b"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.MapUpdate, 'topic -> "map", 'id -> "c1", 'value -> "Map(s -> b, i -> 1, b -> true)")

    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ReceivedStreamStateTransitionUpdate, 'transition -> "Partial.+b,NoChange,NoChange".r)

    clearEvents()

    sendToServiceOnNode1("client/c1", ResetSubscriptionFromStub(Subject("test", "map")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.MapUpdate, 'topic -> "map", 'id -> "c1", 'value -> "Map(s -> b, i -> 1, b -> true)")
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ReceivedStreamStateUpdate, 'state -> "s=b,i=1,b=true".r)

  }

  it should "receive updates with server-side aggregation enabled" in new WithClientConnected {
    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromContents(
          """
            |websocket-server.aggregator.enabled=on
            |websocket-server.aggregator.time-window = 3 s
          """.stripMargin)
      )

    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update1")
    clearEvents()
    sendToServiceOnNode1("test", PublishString("string", "update2"))
    sendToServiceOnNode1("test", PublishString("string", "update3"))
    sendToServiceOnNode1("test", PublishString("string", "update4"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update2")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update3")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update4")
  }

  it should "receive pings when enabled" in new WithClientConnected {
    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromContents(
          """
            |websocket-server.ping.enabled=on
            |websocket-server.ping.interval = 1s
          """.stripMargin)
      )

    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedPing)
  }


  /** TODO - check this one
  it should "drop stream when disconnected" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update1")
    clearEvents()
    sendToServiceOnNode1("client", StopRequest)
    onNode1ExpectExactlyOneEvent(TestServiceActor.Evt.IdleStream, 'stream -> "string")
  }
    */

  it should "send a signal and receive a successful ack" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", SignalFromStub(Subject("test", "signal"), "value", System.currentTimeMillis() + 5000, None, Some("cId")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckOk, 'correlation -> "Some(cId)", 'payload -> "Some(value1)", 'id -> "c1")
  }

  it should "send a signal and receive a failure ack " in new WithClientConnected {
    sendToServiceOnNode1("client/c1", SignalFromStub(Subject("test", "signal_failure"), "value", System.currentTimeMillis() + 5000, None, Some("cId")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckFailed, 'correlation -> "Some(cId)", 'payload -> "Some(failure)", 'id -> "c1")
  }

  it should "send a signal and receive a timeout " in new WithClientConnected {
    sendToServiceOnNode1("client/c1", SignalFromStub(Subject("test", "signal_no_response"), "value", System.currentTimeMillis() + 5000, None, Some("cId")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckFailed, 'correlation -> "Some(cId)", 'payload -> "None", 'id -> "c1")
  }

  it should "send multiple signals and receive successful ack for all, ordered" in new WithClientConnected {
    for (i <- 1 to 100) sendToServiceOnNode1("client/c1", SignalFromStub(Subject("test", "signal"), s"value-$i:", System.currentTimeMillis() + 5000, Some("group"), Some(s"cId:$i")))
    for (i <- 1 to 100) onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckOk, 'correlation -> s"Some(cId:$i)", 'payload -> s"Some(value-$i:$i)", 'id -> "c1")
  }



  it should "receive updates when subscribed with aggregation for a specific stream" in new WithClientConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string"), aggregationIntervalMs = 3000))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "hello")
    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update1")
    clearEvents()
    sendToServiceOnNode1("test", PublishString("string", "update2"))
    sendToServiceOnNode1("test", PublishString("string", "update3"))
    sendToServiceOnNode1("test", PublishString("string", "update4"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update4")
    onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update2")
    onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate, 'topic -> "string", 'id -> "c1", 'value -> "update3")
  }


  trait With3Clients extends With4Nodes {

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor], "client" -> classOf[WebsocketClientStubService], "client2" -> classOf[WebsocketClientStubService])

    override def node2Services = super.node2Services ++ Map("test2" -> classOf[TestServiceActor])

    override def node3Services = super.node3Services ++ Map("client3" -> classOf[WebsocketClientStubService])

    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromFile("websocket-server-defaults"),
        ConfigFromContents(
          """
            |websocket-server.ping.enabled=off
            |websocket-server.partials.enabled=off
            |websocket-server.auth.enabled=off
            |websocket-server.aggregator.enabled=off
          """.stripMargin)
      )


    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "client")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "client2")
    onNode3ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "client3")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "websocket-server")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test2")
    clearEvents()

  }

  "Multiple websocket clients" should "successfully connect to the same endpoint and upgrade to websocket" in new With3Clients {
    sendToServiceOnNode1("client", StartWebsocketClient("c1", "localhost", 8080))
    sendToServiceOnNode1("client2", StartWebsocketClient("c2", "localhost", 8080))
    sendToServiceOnNode3("client3", StartWebsocketClient("c3", "localhost", 8080))
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded, 'id -> "c1")
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded, 'id -> "c2")
    onNode3ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded, 'id -> "c3")
  }

  trait With3ClientsConnected extends With3Clients {
    sendToServiceOnNode1("client", StartWebsocketClient("c1", "localhost", 8080))
    sendToServiceOnNode1("client2", StartWebsocketClient("c2", "localhost", 8080))
    sendToServiceOnNode3("client3", StartWebsocketClient("c3", "localhost", 8080))
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded, 'id -> "c1")
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded, 'id -> "c2")
    onNode3ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded, 'id -> "c3")
    clearEvents()
  }


  it should "subscribe to different streams and receive updates" in new With3ClientsConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode1("client2/c2", OpenSubscriptionFromStub(Subject("test2", "stringX")))
    sendToServiceOnNode3("client3/c3", OpenSubscriptionFromStub(Subject("test", "stringX")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test2", 'topic -> "stringX", 'id -> "c2", 'value -> "helloX")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringX", 'id -> "c3", 'value -> "helloX")

    clearEvents()

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c1", 'value -> "update1")

    sendToServiceOnNode2("test2", PublishString("stringX", "update2"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test2", 'topic -> "stringX", 'id -> "c2", 'value -> "update2")

    sendToServiceOnNode1("test", PublishString("stringX", "update3"))
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringX", 'id -> "c3", 'value -> "update3")

  }

  it should "subscribe to same stream and receive updates" in new With3ClientsConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode1("client2/c2", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode3("client3/c3", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c2", 'value -> "hello")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c3", 'value -> "hello")

    clearEvents()

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c1", 'value -> "update1")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c2", 'value -> "update1")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c3", 'value -> "update1")
  }

  it should "not be affected by some client dropping" in new With3ClientsConnected {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode1("client2/c2", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode3("client3/c3", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c2", 'value -> "hello")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c3", 'value -> "hello")

    clearEvents()

    sendToServiceOnNode1("client", StopRequest)
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.PostStop, 'id -> "c1")
    clearEvents()

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c2", 'value -> "update1")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c3", 'value -> "update1")
  }

  it should "be able to connect to different endpoint, subscribe to stream and get updates" in new With4Nodes {

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor], "client" -> classOf[WebsocketClientStubService], "client2" -> classOf[WebsocketClientStubService])

    override def node2Services = super.node2Services ++ Map("test2" -> classOf[TestServiceActor])

    override def node3Services = super.node3Services ++ Map("client3" -> classOf[WebsocketClientStubService])

    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromFile("websocket-server-defaults"),
        ConfigFromContents(
          """
            |websocket-server.ping.enabled=off
            |websocket-server.partials.enabled=off
            |websocket-server.auth.enabled=off
            |websocket-server.aggregator.enabled=off
          """.stripMargin)
      )

    override def node2Configs: Seq[ConfigReference] = super.node2Configs ++
      Seq(
        ConfigFromFile("websocket-server-defaults"),
        ConfigFromContents(
          """
            |websocket-server.endpoint-port = 8081
            |websocket-server.ping.enabled=off
            |websocket-server.partials.enabled=off
            |websocket-server.auth.enabled=off
            |websocket-server.aggregator.enabled=off
          """.stripMargin)
      )

    override def node3Configs: Seq[ConfigReference] = super.node3Configs ++
      Seq(
        ConfigFromFile("websocket-server-defaults"),
        ConfigFromContents(
          """
            |websocket-server.endpoint-port = 8082
            |websocket-server.ping.enabled=off
            |websocket-server.partials.enabled=off
            |websocket-server.auth.enabled=off
            |websocket-server.aggregator.enabled=off
          """.stripMargin)
      )


    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "client")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "client2")
    onNode3ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "client3")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "websocket-server")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "websocket-server")
    onNode3ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "websocket-server")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test")
    onNode2ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test2")
    clearEvents()


    sendToServiceOnNode1("client", StartWebsocketClient("c1", "localhost", 8080))
    sendToServiceOnNode1("client2", StartWebsocketClient("c2", "localhost", 8081))
    sendToServiceOnNode3("client3", StartWebsocketClient("c3", "localhost", 8082))
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded, 'id -> "c1")
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded, 'id -> "c2")
    onNode3ExpectExactlyOneEvent(WebsocketClientStubService.Evt.ConnectionUpgraded, 'id -> "c3")
    clearEvents()


    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode1("client2/c2", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode3("client3/c3", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c2", 'value -> "hello")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c3", 'value -> "hello")

    clearEvents()

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c1", 'value -> "update1")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c2", 'value -> "update1")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c3", 'value -> "update1")
  }


  trait With3ClientsConnectedAuthEnabled extends With3ClientsConnected {

    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromContents(
          """
            |websocket-server.auth.enabled=on
          """.stripMargin),
        ConfigFromFile("auth-service")
      )

  }


  "With auth enabled, multiple clients" should "subscribe to same stream and receive updates without authentication if service does not require auth" in new With3ClientsConnectedAuthEnabled {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode1("client2/c2", OpenSubscriptionFromStub(Subject("test", "string")))
    sendToServiceOnNode3("client3/c3", OpenSubscriptionFromStub(Subject("test", "string")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c2", 'value -> "hello")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c3", 'value -> "hello")

    onNode1ExpectOneOrMoreEvents(TestServiceActor.Evt.SubjectMapped, 'stream -> "string", 'subj -> "[+]ut:".r)
    clearEvents()

    sendToServiceOnNode1("test", PublishString("string", "update1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c1", 'value -> "update1")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c2", 'value -> "update1")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "string", 'id -> "c3", 'value -> "update1")

  }

  it should "be able to authenticate with credentials" in new With3ClientsConnectedAuthEnabled {
    sendToServiceOnNode1("client/c1",
      SignalFromStub(
        Subject("auth", "authenticate"),
        Json.obj("l" -> "user1", "p" -> "password123").toString(),
        System.currentTimeMillis() + 8000, None, Some("auth1")))
    sendToServiceOnNode1("client2/c2",
      SignalFromStub(
        Subject("auth", "authenticate"),
        Json.obj("l" -> "user2", "p" -> "password123").toString(),
        System.currentTimeMillis() + 8000, None, Some("auth2")))
    sendToServiceOnNode3("client3/c3",
      SignalFromStub(
        Subject("auth", "authenticate"),
        Json.obj("l" -> "user1", "p" -> "password123").toString(),
        System.currentTimeMillis() + 8000, None, Some("auth3")))

    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckOk, 'correlation -> "Some(auth1)", 'payload -> "Some(true)", 'id -> "c1")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckOk, 'correlation -> "Some(auth2)", 'payload -> "Some(true)", 'id -> "c2")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckOk, 'correlation -> "Some(auth3)", 'payload -> "Some(true)", 'id -> "c3")

    onNode1ExpectExactlyNEvents(2, UserAuthenticationSysevents.SuccessfulCredentialsAuth, 'userid -> "user1")
    onNode1ExpectExactlyNEvents(1, UserAuthenticationSysevents.SuccessfulCredentialsAuth, 'userid -> "user2")


    clearEvents()

    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("auth", "token")))
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "auth", 'topic -> "token")
    val auth1Token = locateFirstEventFieldValue(WebsocketClientStubService.Evt.StringUpdate, "value")

    clearEvents()


    sendToServiceOnNode1("client2/c2", OpenSubscriptionFromStub(Subject("auth", "token")))
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "auth", 'topic -> "token")
    val auth2Token = locateFirstEventFieldValue(WebsocketClientStubService.Evt.StringUpdate, "value")

    clearEvents()

    sendToServiceOnNode3("client3/c3", OpenSubscriptionFromStub(Subject("auth", "token")))
    onNode3ExpectExactlyOneEvent(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "auth", 'topic -> "token", 'value -> auth1Token)
    // same token as the same user
    val auth3Token = locateFirstEventFieldValue(WebsocketClientStubService.Evt.StringUpdate, "value")


  }


  trait With3AuthenticatedClients extends With3ClientsConnectedAuthEnabled {
    sendToServiceOnNode1("client/c1",
      SignalFromStub(
        Subject("auth", "authenticate"),
        Json.obj("l" -> "user1", "p" -> "password123").toString(),
        System.currentTimeMillis() + 8000, None, Some("auth1")))
    sendToServiceOnNode1("client2/c2",
      SignalFromStub(
        Subject("auth", "authenticate"),
        Json.obj("l" -> "user2", "p" -> "password123").toString(),
        System.currentTimeMillis() + 8000, None, Some("auth2")))
    sendToServiceOnNode3("client3/c3",
      SignalFromStub(
        Subject("auth", "authenticate"),
        Json.obj("l" -> "user1", "p" -> "password123").toString(),
        System.currentTimeMillis() + 8000, None, Some("auth3")))

    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckOk, 'correlation -> "Some(auth1)", 'payload -> "Some(true)", 'id -> "c1")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckOk, 'correlation -> "Some(auth2)", 'payload -> "Some(true)", 'id -> "c2")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckOk, 'correlation -> "Some(auth3)", 'payload -> "Some(true)", 'id -> "c3")

    onNode1ExpectExactlyNEvents(2, UserAuthenticationSysevents.SuccessfulCredentialsAuth, 'userid -> "user1")
    onNode1ExpectExactlyNEvents(1, UserAuthenticationSysevents.SuccessfulCredentialsAuth, 'userid -> "user2")


    clearEvents()

    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("auth", "token")))
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "auth", 'topic -> "token")
    val auth1Token = locateFirstEventFieldValue(WebsocketClientStubService.Evt.StringUpdate, "value").asInstanceOf[String]

    clearEvents()


    sendToServiceOnNode1("client2/c2", OpenSubscriptionFromStub(Subject("auth", "token")))
    onNode1ExpectExactlyOneEvent(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "auth", 'topic -> "token")
    val auth2Token = locateFirstEventFieldValue(WebsocketClientStubService.Evt.StringUpdate, "value").asInstanceOf[String]

    clearEvents()

    sendToServiceOnNode3("client3/c3", OpenSubscriptionFromStub(Subject("auth", "token")))
    onNode3ExpectExactlyOneEvent(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "auth", 'topic -> "token", 'value -> auth1Token)
    // same token as the same user
    val auth3Token = locateFirstEventFieldValue(WebsocketClientStubService.Evt.StringUpdate, "value").asInstanceOf[String]

    clearEvents()

  }

  it should "be able to re-authenticate using token" in new With3AuthenticatedClients {
    sendToServiceOnNode1("client/c1",
      SignalFromStub(
        Subject("auth", "authenticate"),
        Json.obj("t" -> auth1Token).toString(),
        System.currentTimeMillis() + 8000, None, Some("auth1")))
    onNode1ExpectExactlyOneEvent(UserAuthenticationSysevents.SuccessfulTokenAuth, 'authkey -> auth1Token.r)
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.ReceivedSignalAckOk, 'correlation -> "Some(auth1)", 'payload -> "Some(true)", 'id -> "c1")
  }

  it should "be able to subscribe to user-specific stream and receive independent updates" in new With3AuthenticatedClients {
    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "stringWithId")))
    sendToServiceOnNode1("client2/c2", OpenSubscriptionFromStub(Subject("test", "stringWithId")))
    sendToServiceOnNode3("client3/c3", OpenSubscriptionFromStub(Subject("test", "stringWithId")))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringWithId", 'id -> "c1", 'value -> "hello")
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringWithId", 'id -> "c2", 'value -> "hello")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringWithId", 'id -> "c3", 'value -> "hello")

    clearEvents()

    sendToServiceOnNode1("test", PublishString(StreamId("string", Some("user1")), "update-user1"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringWithId", 'id -> "c1", 'value -> "update-user1")
    onNode3ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringWithId", 'id -> "c3", 'value -> "update-user1")

    duringPeriodInMillis(2000) {
      onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringWithId", 'id -> "c2")
    }

    clearEvents()

    sendToServiceOnNode1("test", PublishString(StreamId("string", Some("user2")), "update-user2"))
    onNode1ExpectOneOrMoreEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringWithId", 'id -> "c2", 'value -> "update-user2")

    duringPeriodInMillis(2000) {
      onNode1ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringWithId", 'id -> "c1")
      onNode3ExpectNoEvents(WebsocketClientStubService.Evt.StringUpdate, 'sourceService -> "test", 'topic -> "stringWithId", 'id -> "c3")
    }


  }


}
