package rs.core.stream

import rs.core.services.BaseServiceActor

trait StreamPublishers extends DictionaryMapStreamPublisher with ListStreamPublisher with SetStreamPublisher with StringStreamPublisher {
  self: BaseServiceActor =>
}


