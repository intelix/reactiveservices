package rs.core

case class ServiceKey(id: String) {
  override def toString: String = id
}

object ServiceKey {

  import scala.language.implicitConversions

  implicit def toServiceKey(id: String): ServiceKey = ServiceKey(id)


}



