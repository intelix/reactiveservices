package rs.core.stream

import rs.core.services.{FSMServiceCell, ServiceCell}

trait StreamPublishers extends DictionaryMapStreamPublisher with ListStreamPublisher with SetStreamPublisher with StringStreamPublisher {
  self: FSMServiceCell =>
}


