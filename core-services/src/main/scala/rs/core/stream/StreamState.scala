package rs.core.stream

trait StreamState {
  def transitionFrom(olderState: Option[StreamState]): Option[StreamStateTransition]
}

trait StreamStateTransition {
  def toNewStateFrom(state: Option[StreamState]): Option[StreamState]
  def applicableTo(state: Option[StreamState]): Boolean
}


