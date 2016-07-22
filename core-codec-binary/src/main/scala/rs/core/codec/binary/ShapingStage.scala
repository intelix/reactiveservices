package rs.core.codec.binary

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.GraphDSL.Implicits.flow2flow
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL}
import akka.stream.stage._
import akka.util.ByteString
import rs.core.config.ConfigOps.wrap
import rs.core.config.{NodeConfig, ServiceConfig}
import rs.core.services.endpoint.akkastreams.BytesStageBuilder

import scala.concurrent.duration._
import scala.language.postfixOps


class ShapingStage extends BytesStageBuilder {
  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: NodeConfig): Option[BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed]] =
    if (serviceCfg.asBoolean("shaping.enabled", defaultValue = true)) Some(BidiFlow.fromGraph(GraphDSL.create() { b =>
      val in = b.add(Flow[ByteString].buffer(2, OverflowStrategy.backpressure))
      val out = b.add(Flow.fromGraph(new SimpleShapedFlow(serviceCfg)))
      BidiShape.fromFlows(in, out)
    }))
    else None
}


private class SimpleShapedFlow(serviceCfg: ServiceConfig) extends GraphStage[FlowShape[ByteString, ByteString]] {
  val in: Inlet[ByteString] = Inlet("Inbound")
  val out: Outlet[ByteString] = Outlet("ShapedOut")

  override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {

    val bSec = serviceCfg.asLong("shaping.target-kb-sec-per-session", 1024) * 1024
    val checkInterval = serviceCfg.asFiniteDuration("shaping.checkpoint-interval", 2 seconds)

    case object Timer

    var onHold: Option[ByteString] = None
    var allowed: Long = 0

    override def preStart(): Unit = {
      schedulePeriodically(Timer, checkInterval)
      pull(in)
      refresh()
    }

    override protected def onTimer(timerKey: Any): Unit = refresh()

    def refresh() = {
      allowed = bSec * checkInterval.toMillis / 1000
      forward()
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val next = grab(in)
        if (canPush) pushNext(next) else onHold = Some(next)
      }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        forward()
      }
    })

    def pushNext(x: ByteString) = {
      push(out, x)
      pull(in)
      account(x.size)
    }


    def account(x: Int): Unit = allowed -= x

    def canPush: Boolean = isAvailable(out) && allowed > 0

    def forward() = onHold foreach { next =>
      if (canPush) {
        pushNext(next)
        onHold = None
      }
    }

  }
}
