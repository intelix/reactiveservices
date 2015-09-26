package rs.core.stream

import rs.core.services.ServiceCell

trait StreamPublishers extends DictionaryMapStreamPublisher with ListStreamPublisher with SetStreamPublisher with StringStreamPublisher {
  self: ServiceCell =>
}


