package rs.node.core

import akka.actor.Props
import akka.remote._
import rs.core.actors.{BasicActor, BaseActorSysevents}
import rs.node.core.AssociationMonitorActor.Evt


object AssociationMonitorActor {
  def props = Props[AssociationMonitorActor]

  trait Evt extends BaseActorSysevents {
    override def componentId: String = "AssociationMonitor"
  }

  object Evt extends Evt

}


class AssociationMonitorActor extends BasicActor with Evt {

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.system.eventStream.subscribe(self, classOf[AssociationEvent])
  }

  onMessage {
    case AssociatedEvent(_, remote, _) =>
    case DisassociatedEvent(_, remote, _) =>
    case AssociationErrorEvent(cause, _, remote, _, _) => println(s"!>>> AssociationErrorEvent: $cause")
  }

}
