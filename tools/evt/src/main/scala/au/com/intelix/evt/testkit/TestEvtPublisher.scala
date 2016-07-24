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
package au.com.intelix.evt.testkit

import au.com.intelix.evt.{Evt, EvtFieldValue, EvtPublisher, EvtSource}


object TestEvtPublisher {

  private var evts = List[RaisedEvent]()

  def +(r: RaisedEvent) = TestEvtPublisher.synchronized {
    evts +:= r
  }

  def clearComponentEvents(sourceId: String) = TestEvtPublisher.synchronized {
    evts = evts filter(_.source.evtSourceId != sourceId)
  }

  def clear() = TestEvtPublisher.synchronized {
    evts = List()
  }

  def withOrderedEvents(f: List[RaisedEvent] => Unit) = this.synchronized {
    f(evts.reverse)
  }

  def events = evts.reverse

  def eventsFor(s: EvtSelection) = evts.filter{ e => e.event == s.e && (s.s.isEmpty || s.s.contains(e.source))}.reverse

}

class TestEvtPublisher extends EvtPublisher {


  override def raise(s: EvtSource, e: Evt, fields: Seq[EvtFieldValue]): Unit =
    TestEvtPublisher + RaisedEvent(System.currentTimeMillis(), s, e, fields)


  override def canPublish(s: EvtSource, e: Evt): Boolean = true
}




