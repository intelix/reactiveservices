package rs.core.stream

import rs.core.services.BaseServiceCell

trait StreamPublishers extends DictionaryMapStreamPublisher with ListStreamPublisher with SetStreamPublisher with StringStreamPublisher {
  self: BaseServiceCell =>
}


