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




