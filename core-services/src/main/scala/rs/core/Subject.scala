package rs.core

// TODO refactor keys into Tags
case class Subject(service: ServiceKey, topic: TopicKey, keys: String = "") {
  @transient private lazy val asString = service.toString + "|" + topic.toString + (if (keys == "") "" else "|" + keys)

  override def toString: String = asString

  def withKeys(otherKeys: String) = this.copy(keys = keys + otherKeys)

  //  def isSupersetOf(s: Subject) = service == s.service && topic == s.topic && keys.contains(s.keys) // TODO better keys comparison!
  //  def isSubsetOf(s: Subject) = s.isSupersetOf(this)
}



