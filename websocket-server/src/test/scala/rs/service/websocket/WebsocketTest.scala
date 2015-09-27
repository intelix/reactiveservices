package rs.service.websocket

import org.scalatest.FlatSpec
import rs.core.Subject
import rs.node.core.ServiceNodeActor
import rs.service.websocket.WebsocketClientStubService.{Evt, OpenSubscriptionFromStub, StartWebsocketClient}
import rs.testing._
import rs.testing.components.TestServiceActor
import rs.testing.components.TestServiceActor.PublishMap

class WebsocketTest extends FlatSpec with ManagedNodeTestContext with IsolatedActorSystems {


  trait With4NodesAndTestOn1 extends With4Nodes {

    override def allNodesConfigs: Seq[ConfigReference] = super.allNodesConfigs :+ ConfigFromContents("node.cluster.discovery.timeout=1s")

    override def node1Services = super.node1Services ++ Map("test" -> classOf[TestServiceActor], "client" -> classOf[WebsocketClientStubService])

    override def node1Configs: Seq[ConfigReference] = super.node1Configs ++
      Seq(
        ConfigFromFile("websocket-server-defaults"),
        ConfigFromContents("websocket-server.ping.enabled=off"),
        ConfigFromContents("websocket-server.partials.enabled=on"),
        ConfigFromContents("websocket-server.auth.enabled=off"),
        ConfigFromContents("websocket-server.aggregator.enabled=off")
      )
  }

  "Websocket" should "work" in new With4NodesAndTestOn1 {
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "client")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "websocket-server")
    onNode1ExpectExactlyOneEvent(ServiceNodeActor.Evt.StartingService, 'service -> "test")
    clearEvents()

    sendToServiceOnNode1("client", StartWebsocketClient("c1", "localhost", 8080))
    onNode1ExpectExactlyOneEvent(Evt.ConnectionUpgraded)
    clearEvents()

    sendToServiceOnNode1("client/c1", OpenSubscriptionFromStub(Subject("test", "map")))
    sendToServiceOnNode1("test", PublishMap("map", Array("a", 123, false)))

    collectAndPrintEvents()

    sendToServiceOnNode1("test", PublishMap("map", Array("a", 123, false)))

    collectAndPrintEvents()

  }

}
