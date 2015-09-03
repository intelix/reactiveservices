package rs.core.services

trait WithMessageId {
  def messageId: MessageId
}

//
//trait WithGeneratedId extends WithMessageId {
//  override val messageId: MessageId = RandomStringMessageId()
//}
