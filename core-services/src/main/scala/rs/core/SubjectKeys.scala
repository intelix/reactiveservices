package rs.core

object SubjectKeys {

  trait KeyOps {

    val token: String
    val tokenPrefix = "+"
    val tokenPostfix = ":"
    private lazy val completeToken = tokenPrefix + token + tokenPostfix

    final def apply(value: String) = completeToken + value

    final def unapply(value: String): Option[String] = {
      val xxx = value.indexOf(completeToken) match {
        case -1 => None
        case i => value.indexOf(tokenPrefix, i + 1) match {
          case -1 => Some(value.substring(i + completeToken.length))
          case j => Some(value.substring(i + completeToken.length, j))
        }
      }
      println(s"!>>> $value unapplied to $xxx for $token")
      xxx
    }
  }


  object UserToken extends KeyOps {
    override val token: String = "ut"
  }

  object UserId extends KeyOps {
    override val token: String = "uid"
  }


}
