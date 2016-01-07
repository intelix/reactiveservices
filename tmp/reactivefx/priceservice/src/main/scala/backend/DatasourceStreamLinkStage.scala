package backend

import akka.actor.ActorRef
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import backend.DatasourceStreamLinkStage.LinkRef
import com.typesafe.scalalogging.StrictLogging

import scala.collection.immutable.Queue
import scala.language.postfixOps

object DatasourceStreamLinkStage {
  case class LinkRef(ref: ActorRef)
  def apply(parentRef: ActorRef) = Flow.fromGraph(new DatasourceStreamLinkStage(parentRef))
}

private class DatasourceStreamLinkStage(parentRef: ActorRef) extends GraphStage[FlowShape[ApplicationMessage, ApplicationMessage]] {

  val in: Inlet[ApplicationMessage] = Inlet("ClientBound")
  val out: Outlet[ApplicationMessage] = Outlet("DatasourceBound")

  override val shape: FlowShape[ApplicationMessage, ApplicationMessage] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StrictLogging {

    lazy val self = getStageActorRef(onMessage)
    var pendingToDatasource: Queue[ApplicationMessage] = Queue()

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        parentRef ! grab(in)
        pull(in)
      }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pushToDatasource()
    })

    override def preStart(): Unit = {
      parentRef ! LinkRef(self)
      println("!>>> Started")
      pull(in)
    }

    private def onMessage(x: (ActorRef, Any)): Unit = x match {
      case (_, m: ServerToClient) =>
        pendingToDatasource = pendingToDatasource :+ m
        println(s"!>>> pendingToDatasource: $pendingToDatasource")
        pushToDatasource()
      case (_, el) =>
        println("!>>> Oh... " + el)
        logger.warn(s"Unexpected: $el")
    }

    private def pushToDatasource() = if (isAvailable(out) && pendingToDatasource.nonEmpty)
      pendingToDatasource.dequeue match {
        case (el, queue) =>
          push(out, el)
          pendingToDatasource = queue
      }

  }


}


