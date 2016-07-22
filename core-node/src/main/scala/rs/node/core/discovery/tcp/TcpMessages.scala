package rs.node.core.discovery.tcp

import akka.actor.{Address, AddressFromURIString}
import akka.util.ByteString


object TcpMessages {

  case class RemoteClusterView(members: Set[String], roles: Set[String]) {
    lazy val toByteString = ByteString("!" + (if (members.isEmpty) "none" else members.mkString(",")) + ";" + roles.mkString(","))
  }

  object RemoteClusterView {
    private def convertSeed(str: String) = str match {
      case "none" => None
      case s => Some(AddressFromURIString(s))
    }

    def unapply(bs: ByteString): Option[(Set[String], Set[String])] =
      bs.utf8String match {
        case s if s.startsWith("!") => s drop 1 split ";" match {
          case Array("none") => Some((Set.empty[String], Set.empty[String]))
          case Array("none", _) => Some((Set.empty[String], Set.empty[String]))
          case Array(m, rs) => Some((m.split(",").toSet, rs.split(",").toSet))
          case Array(m) => Some((m.split(",").toSet, Set.empty[String]))
          case _ => None
        }
        case _ => None
      }
  }

}
