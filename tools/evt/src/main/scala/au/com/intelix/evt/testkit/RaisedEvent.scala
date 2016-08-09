package au.com.intelix.evt.testkit

import au.com.intelix.evt.{Evt, EvtSource, _}

case class RaisedEvent(timestamp: Long, source: EvtSource, event: String, lvl: EvtLevel, values: Seq[EvtFieldValue])
