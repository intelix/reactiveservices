package rs.core

case class TopicKey(id: String) {
  override def toString: String = id
}

object TopicKey {

  import scala.language.implicitConversions

  implicit def toTopicKey(id: String): TopicKey = TopicKey(id)
}


object CompositeTopicKey {
  def apply(prefix: String, entityId: String) = new TopicKey(prefix + ":" + entityId)

  def unapply(t: TopicKey): Option[(String, String)] = t.id match {
    case s if s.contains(':') =>
      val idx = s.indexOf(':')
      Some((s.substring(0, idx), s.substring(idx + 1)))
    case _ => None
  }
}

object ComplexTopicKey {
  def apply(prefix: String, entityId: String*) = new TopicKey(prefix + ":" + entityId.mkString("~"))

  def unapply(t: TopicKey): Option[(String, Array[String])] = t.id match {
    case s if s.contains(':') =>
      val idx = s.indexOf(':')
      Some((s.substring(0, idx), s.substring(idx + 1).split('~')))
    case _ => None
  }
}


