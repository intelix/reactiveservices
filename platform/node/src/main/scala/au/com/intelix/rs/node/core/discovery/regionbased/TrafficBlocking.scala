package au.com.intelix.rs.node.core.discovery.regionbased

trait TrafficBlocking
object TrafficBlocking {
  case class BlockCommunicationWith(host: String, port: Int) extends TrafficBlocking
  case class UnblockCommunicationWith(host: String, port: Int) extends TrafficBlocking
}
