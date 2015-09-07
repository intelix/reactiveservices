package rs.core.services.internal

import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents

trait ConsumerDemandTrackerSysevents extends ComponentWithBaseSysevents {
  val DemandRegistered = "DemandRegistered".trace
  val DemandFulfilled = "DemandFulfilled".trace
  val UnableToFulfillNoDemand = "UnableToFulfillNoDemand".trace
}

trait ConsumerDemandTracker extends ConsumerDemandTrackerSysevents with WithSyseventPublisher {

  var currentDemand = 0L

  def addConsumerDemand(count: Long) = {
    currentDemand += count
    DemandRegistered('new -> count, 'total -> currentDemand)
  }

  def hasDemand = currentDemand > 0

  def fulfillDownstreamDemandWith(f: => Unit) = {
    if (hasDemand) {
      f
      currentDemand -= 1
      DemandFulfilled('count -> 1, 'remaining -> currentDemand)
    } else {
      UnableToFulfillNoDemand()
    }
  }
}