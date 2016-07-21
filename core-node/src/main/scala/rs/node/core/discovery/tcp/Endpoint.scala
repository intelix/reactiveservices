package rs.node.core.discovery.tcp

import java.net.InetSocketAddress

private[tcp] case class Endpoint(uri: String, host: String, port: Int, addr: InetSocketAddress)