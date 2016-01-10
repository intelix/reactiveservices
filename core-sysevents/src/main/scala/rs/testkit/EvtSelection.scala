package rs.testkit

import rs.core.evt.{Evt, EvtSource}


case class EvtSelection(e: Evt, s: Option[EvtSource] = None)
