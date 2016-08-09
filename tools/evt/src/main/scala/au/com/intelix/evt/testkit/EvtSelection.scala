package au.com.intelix.evt.testkit

import au.com.intelix.evt.{Evt, EvtSource}

case class EvtSelection(e: String, s: Option[EvtSource] = None)
