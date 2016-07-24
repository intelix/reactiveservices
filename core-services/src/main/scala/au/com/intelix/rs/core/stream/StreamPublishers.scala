package au.com.intelix.rs.core.stream

import au.com.intelix.rs.core.services.BaseServiceActor

trait StreamPublishers extends DictionaryMapStreamPublisher with ListStreamPublisher with SetStreamPublisher with StringStreamPublisher {
  self: BaseServiceActor =>
}


