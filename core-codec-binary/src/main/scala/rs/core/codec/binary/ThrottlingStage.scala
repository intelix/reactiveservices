package rs.core.codec.binary

import akka.stream._
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL}
import akka.stream.stage._
import rs.core.codec.binary.BinaryProtocolMessages.{BinaryDialectInbound, BinaryDialectOutbound, HighPriority}
import rs.core.config.ConfigOps.wrap
import rs.core.config.{NodeConfig, ServiceConfig}

import scala.concurrent.duration._
import scala.language.postfixOps


class ThrottlingStage extends BinaryDialectStageBuilder {
  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: NodeConfig): Option[BidiFlow[BinaryDialectInbound, BinaryDialectInbound, BinaryDialectOutbound, BinaryDialectOutbound, Unit]] =
    if (serviceCfg.asBoolean("throttling.enabled", defaultValue = true)) Some(BidiFlow.fromGraph(GraphDSL.create() { b =>
      val in = b.add(Flow[BinaryDialectInbound])
      val out = b.add(Flow.fromGraph(new SimpleThrottledFlow(serviceCfg)))
      BidiShape.fromFlows(in, out)
    }))
    else None
}


private class SimpleThrottledFlow(serviceCfg: ServiceConfig) extends GraphStage[FlowShape[BinaryDialectOutbound, BinaryDialectOutbound]] {
  val in: Inlet[BinaryDialectOutbound] = Inlet("Inbound")
  val out: Outlet[BinaryDialectOutbound] = Outlet("ThrottledOut")

  override val shape: FlowShape[BinaryDialectOutbound, BinaryDialectOutbound] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {

    val msgSec = serviceCfg.asInt("throttling.total-msg-sec-per-session", 1000)
    println(s"!>>>> $msgSec ")
    val tokenReplenishInterval = serviceCfg.asFiniteDuration("throttling.token-replenish-interval", 200 millis)

    case object TokensTimer

    val TokensCount = msgSec * tokenReplenishInterval.toMillis / 1000

    var tokens = 0
    var lastTokensIssuedAt = 0L

    var maybeNext: Option[BinaryDialectOutbound] = None

    override def preStart(): Unit = {
      schedulePeriodically(TokensTimer, tokenReplenishInterval)
      pull(in)
    }

    override protected def onTimer(timerKey: Any): Unit = {
      val now = System.currentTimeMillis()
      val elapsedTime = if (lastTokensIssuedAt == 0) tokenReplenishInterval.toMillis else now - lastTokensIssuedAt
      tokens = (elapsedTime * TokensCount / tokenReplenishInterval.toMillis ).toInt
      lastTokensIssuedAt = now
      println(s"!>>>> $elapsedTime, $tokenReplenishInterval, $TokensCount ")
      forwardThrottled()
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        maybeNext = Some(grab(in))
        forwardThrottled()
      }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = forwardThrottled()
    })

    def forwardThrottled() = if (isAvailable(out)) maybeNext foreach { next =>
      if (tokens > 0 || next.isInstanceOf[HighPriority]) {
        pull(in)
        push(out, next)
        maybeNext = None
        if (tokens > 0) tokens -= 1
      }
    }

  }
}
