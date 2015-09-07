package rs.core

// TODO refactor keys into Tags
case class Subject(service: ServiceKey, topic: TopicKey, keys: String = "") {
  @transient private lazy val asString = service.toString + "|" + topic.toString + (if (keys == "") "" else "|" + keys)

  override def toString: String = asString

  def withKeys(otherKeys: String) = this.copy(keys = keys + otherKeys)
  def withKeys(otherKeys: Option[String]) = otherKeys match {
    case Some(k) => this.copy(keys = keys + k)
    case None => this
  }

}



