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

import rs.core.sysevents._
import rs.core.sysevents.log.LoggerEvtPublisher

case class RaisedEvent(timestamp: Long, event: Sysevent, values: Seq[FieldAndValue])

object TestEvtPublisher {

  @volatile var events = Map[Sysevent, List[Seq[FieldAndValue]]]()
  private var eventsInOrder = List[RaisedEvent]()

  def clear() = TestEvtPublisher.synchronized {
    events = Map()
    eventsInOrder = List()
  }

  def clearComponentEvents(componentId: String) = TestEvtPublisher.synchronized {
    events = events.filter(_._1.componentId == componentId)
    eventsInOrder = eventsInOrder.filter(_.event.componentId != componentId)
  }

  def withOrderedEvents(f: List[RaisedEvent] => Unit) = this.synchronized {
    f(eventsInOrder)
  }

}

class TestEvtPublisher extends LoggerEvtPublisher {

  override def publish(ctx: EvtContext): Unit = {
    TestEvtPublisher.synchronized {
      val cwf = ctx.asInstanceOf[EvtContextWithFields]
      TestEvtPublisher.eventsInOrder = TestEvtPublisher.eventsInOrder :+ RaisedEvent(System.currentTimeMillis(), cwf.event, cwf.fields)
      TestEvtPublisher.events += (cwf.event -> (TestEvtPublisher.events.getOrElse(cwf.event, List()) :+ cwf.fields.map {
        case (f, v) => f -> transformValue(v)
      }))
    }
  }


}




