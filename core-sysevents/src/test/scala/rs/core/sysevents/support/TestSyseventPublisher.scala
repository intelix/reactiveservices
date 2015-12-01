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
import rs.core.sysevents.log.LoggerSyseventPublisher

case class RaisedEvent(timestamp: Long, event: Sysevent, values: Seq[FieldAndValue])

class TestSyseventPublisher extends LoggerSyseventPublisher {

  @volatile var events = Map[Sysevent, List[Seq[FieldAndValue]]]()
  private var eventsInOrder = List[RaisedEvent]()

  def withOrderedEvents(f: List[RaisedEvent] => Unit) = this.synchronized {
    f(eventsInOrder)
  }

  def clear() = this.synchronized {
    events = Map()
    eventsInOrder = List()
  }

  def clearComponentEvents(componentId: String) = this.synchronized {
    events = events.filter(_._1.componentId == componentId)
    eventsInOrder = eventsInOrder.filter(_.event.componentId != componentId)
  }


  override def publish(ctx: SyseventPublisherContext): Unit = {
    this.synchronized {
      val cwf = ctx.asInstanceOf[ContextWithFields]
      eventsInOrder = eventsInOrder :+ RaisedEvent(System.currentTimeMillis(), cwf.event, cwf.fields)
      events += (cwf.event -> (events.getOrElse(cwf.event, List()) :+ cwf.fields.map {
        case (f, v) => f -> transformValue(v)
      }))
    }
  }


}




