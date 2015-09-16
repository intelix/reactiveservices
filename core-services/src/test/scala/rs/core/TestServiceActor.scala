package rs.core

import rs.core.actors.{BaseActorSysevents, ClusterAwareness}
import rs.core.services.{StreamId, ServiceCellSysevents, ServiceCell}
import rs.core.stream.StringStreamPublisher

trait TestServiceActorEvents extends ServiceCellSysevents {

  val LeaderChanged = "LeaderChanged".info

  override def componentId: String = "Test"
}

object TestServiceActorEvents extends TestServiceActorEvents

class TestServiceActor(id: String) extends ServiceCell(id) with TestServiceActorEvents with ClusterAwareness with StringStreamPublisher {

  override def onLeaderChanged(): Unit = LeaderChanged('self -> cluster.selfAddress.toString)

  onStreamActive {
    case StreamId("string",_) => "string" !~ "hello!"
  }

  onSubjectSubscription {
    case Subject(_, TopicKey("string"), _) => Some("string")
  }

  onSignal {
    case (_, s) =>
      println(s"!>>> Hey....")
      Some(SignalOk(Some("Well done - " + s)))
    //    None
  }

}

