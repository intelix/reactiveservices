/*
 * Copyright 2014-16 Intelix Pty Ltd
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
package rs.testkit

import rs.core.evt.{Evt, EvtPublisher, EvtSource}
import rs.core.sysevents.{Sysevent, FieldAndValue}

case class RaisedEvent(timestamp: Long, source: EvtSource, event: Evt, values: Seq[(String, Any)])

object TestEvtPublisher {
  def clearComponentEvents(componentId: String) = {} // TODO !>>>>


  // TODO REMOVE !!!! !>>>>>
  @volatile var events = Map[Sysevent, List[Seq[FieldAndValue]]]()

  private var eventsInOrder = List[RaisedEvent]()

  def clear() = TestEvtPublisher.synchronized {
    eventsInOrder = List()
  }

  def withOrderedEvents(f: List[RaisedEvent] => Unit) = this.synchronized {
    f(eventsInOrder)
  }

}

class TestEvtPublisher extends EvtPublisher {

  override def raise(s: EvtSource, e: Evt, fields: Seq[(String, Any)]): Unit =
    TestEvtPublisher.synchronized {
      TestEvtPublisher.eventsInOrder :+= RaisedEvent(System.currentTimeMillis(), s, e, fields)
    }

  override def canPublish(s: EvtSource, e: Evt): Boolean = true
}




