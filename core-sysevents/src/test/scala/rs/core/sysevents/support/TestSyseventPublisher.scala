package rs.core.sysevents.support

import core.sysevents.FieldAndValue
import rs.core.sysevents._

import scala.collection.mutable

case class RaisedEvent(timestamp: Long, event: Sysevent, values: Seq[FieldAndValue])

class TestSyseventPublisher extends SyseventPublisher with LoggerSyseventPublisher {

  val events = mutable.Map[Sysevent, List[Seq[FieldAndValue]]]()
  private var eventsInOrder = List[RaisedEvent]()

  def withOrderedEvents(f: List[RaisedEvent] => Unit) = events.synchronized {
    f(eventsInOrder)
  }

  def clear() = events.synchronized {
    events.clear()
    eventsInOrder = List()
  }
  def clearComponentEvents(componentId: String) = events.synchronized {
    events.keys.filter(_.componentId == componentId).foreach(events.remove)
    eventsInOrder = eventsInOrder.filter(_.event.componentId != componentId)
  }


  override def publish(system: SyseventSystem, event: Sysevent, values: => Seq[FieldAndValue]): Unit = {
    events.synchronized {
      eventsInOrder = eventsInOrder :+ RaisedEvent(System.currentTimeMillis(), event, values)
      events += (event -> (events.getOrElse(event, List()) :+ values.map {
        case (f, v) => f -> transformValue(v)
      }))
    }
    super.publish(system, event, values)
  }

}




