package rs.core.evt


trait EvtPublisher {
  def canPublish(s: EvtSource, e: Evt): Boolean
  def raise(s: EvtSource, e: Evt, fields: Seq[(String, Any)])
}

trait EvtFieldBuilder {
  def +(f: (String, Any)): Unit
  def result: Seq[(String, Any)]
}

class StatefulEvtFieldBuilder(private var list: Seq[(String, Any)]) extends EvtFieldBuilder {
  override def +(f: (String, Any)) = list = f +: list
  override def result = list.reverse
}
