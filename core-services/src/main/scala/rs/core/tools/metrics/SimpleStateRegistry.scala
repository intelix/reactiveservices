package rs.core.tools.metrics

import scala.collection.concurrent.TrieMap

object SimpleStateRegistry {

  val m: TrieMap[String, String] = TrieMap()

  def getPublisherFor(name: String) = new SimpleStatePublisher(name, s => m.put(name, s))

  def remove(id: String) = m -= id

}

class SimpleStatePublisher(private val name: String, private val publisher: String => Unit) extends StatePublisher {
  override def update(state: String): Unit = publisher(state)
}