package rs.core.services.endpoint.akkastreams

import akka.actor.{PoisonPill, ActorRef, Props}
import akka.stream.actor.ActorSubscriberMessage.{OnError, OnComplete, OnNext}
import akka.stream.actor.{ActorSubscriber, RequestStrategy, WatermarkRequestStrategy}
import rs.core.ServiceKey
import rs.core.actors.ActorWithComposableBehavior
import rs.core.registry.RegistryRef
import rs.core.services.Messages._
import rs.core.services.internal.StreamAggregatorActor.ServiceLocationChanged


object ServicePortSubscriptionRequestSink {

  def props(streamAggregator: ActorRef) = Props(classOf[ServicePortSubscriptionRequestSink], streamAggregator)

}


trait ServicePortSubscriptionRequestSink
  extends ActorWithComposableBehavior
  with RegistryRef {

  val streamAggregator: ActorRef

  private def onServiceUnavailable(key: ServiceKey): Unit = {
    logger.info(s"!>>> onServiceUnavailable($key)")

    streamAggregator ! ServiceLocationChanged(key, None)
  }

  private def onServiceAvailable(key: ServiceKey, ref: ActorRef): Unit = {
    logger.info(s"!>>> onServiceAvailable($key, $ref)")
    streamAggregator ! ServiceLocationChanged(key, Some(ref))
  }

  onServiceLocationChanged {
    case (key, None) => onServiceUnavailable(key)
    case (key, Some(ref)) => onServiceAvailable(key, ref)
  }

  def addSubscription(m: OpenSubscription): Unit = {
    val serviceKey = m.subj.service
    streamAggregator ! m
    registerServiceLocationInterest(serviceKey) // this call is idempotent
  }

  def removeSubscription(m: CloseSubscription): Unit = {
    streamAggregator ! m
    // TODO - optimisation - consider closing interest with registry if all requests for the service are closed
  }


}

object ServicePortSubscriptionRequestSinkSubscriber {
  def props(streamAggregator: ActorRef) = Props(classOf[ServicePortSubscriptionRequestSinkSubscriber], streamAggregator)
}

class ServicePortSubscriptionRequestSinkSubscriber(val streamAggregator: ActorRef)
  extends ServicePortSubscriptionRequestSink
  with ActorSubscriber {

  // TODO this 5000/1000 make it configurable
  override protected def requestStrategy: RequestStrategy = new WatermarkRequestStrategy(5000, 1000)

  onMessage {
    case OnNext(m: OpenSubscription) => addSubscription(m)
    case OnNext(m: CloseSubscription) => removeSubscription(m)
    case OnComplete =>
      logger.info("!>>> Received complete")

      // for some reason streams 1.0 don't terminate the actor automatically and it's not happening when cancel() is called as it is already marked
      // as 'cancelled', as OnComplete and OnError are processed in aroundReceive
      cancel()
      context.stop(self)
    case OnError(x) =>
      logger.info(s"!>>> Received complete or error $x")
      x.printStackTrace()

      // for some reason streams 1.0 don't terminate the actor automatically and it's not happening when cancel() is called as it is already marked
      // as 'cancelled', as OnComplete and OnError are processed in aroundReceive
      cancel()
      context.stop(self)
  }


  override def onTerminated(ref: ActorRef): Unit = {
    super.onTerminated(ref)
    if (ref == streamAggregator) {
      logger.info(s"!>>>> $ref terminated.... streamAggregator=$streamAggregator....  actor  stopping")
      cancel()
      context.stop(self)
    }
  }

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    context.watch(streamAggregator)
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    streamAggregator ! PoisonPill
    logger.info("!>>> Stopping ServicePortSubscriptionRequestSinkSubscriber")
    super.postStop()
  }

  override def componentId: String = "ServicePortSubscriptionRequestSink"
}
