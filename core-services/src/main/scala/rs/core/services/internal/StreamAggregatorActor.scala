package rs.core.services.internal

import java.util

import akka.actor.{Actor, ActorRef, Props}
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.Messages._
import rs.core.services.internal.ServiceStreamingEndpointActor._
import rs.core.services.internal.StreamAggregatorActor.ServiceLocationChanged
import rs.core.stream.StreamState
import rs.core.tools.NowProvider
import rs.core.{ServiceKey, Subject}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps


object StreamAggregatorActor {

  case class ServiceLocationChanged(serviceKey: ServiceKey, location: Option[ActorRef])

  def props() = Props(classOf[StreamAggregatorActor])
}


case class TargetConfig(subj: Subject, priorityKey: Option[String] = None, aggregationIntervalMs: Int = 0)

class PriorityBucketGroup(val priorityKey: Option[String]) extends Comparable[PriorityBucketGroup] {
  private lazy val preCalculatedHashCode = priorityKey.hashCode()
  private var idx = 0
  private val buckets: util.ArrayList[Bucket] = new util.ArrayList[Bucket]()

  def publishPending(canUpdate: () => Boolean, send: (Any) => Unit) = if (buckets.size() > 0) {
    val cycles = buckets.size()
    var cnt = 0
    while (cnt < cycles && canUpdate()) {
      if (idx < 0 || idx >= buckets.size()) idx = 0
      buckets.get(idx).publishPending(canUpdate, send)
      idx += 1
      cnt += 1
    }
  }

  def add(b: Bucket) = buckets add b

  def remove(b: Bucket) = buckets remove b

  def isEmpty = buckets.isEmpty


  //noinspection ComparingUnrelatedTypes
  def canEqual(other: Any): Boolean = other.isInstanceOf[PriorityBucketGroup]

  override def equals(other: Any): Boolean = other match {
    case that: PriorityBucketGroup =>
      (that canEqual this) &&
        priorityKey == that.priorityKey
    case _ => false
  }

  override def hashCode(): Int = preCalculatedHashCode

  override def compareTo(o: PriorityBucketGroup): Int = priorityKey match {
    case x if x == o.priorityKey => 0
    case Some(x) if o.priorityKey.exists(_ < x) => 1
    case _ => -1
  }
}

class Bucket(val subj: Subject, val priorityKey: Option[String], aggregationIntervalMs: Int) {

  import NowProvider._

  var lastUpdatePublishedAt = 0L
  private var pendingState: Option[StreamState] = None
  private var state: Option[StreamState] = None

  def publishPending(canUpdate: () => Boolean, send: Any => Unit) = {

    if (canUpdate() && pendingState.isDefined && isAggregationCriteriaMet) pendingState foreach { pending =>
      lastUpdatePublishedAt = now
      send(StreamStateUpdate(subj, pending))
      state = pendingState
      pendingState = None
    }
  }

  private def isAggregationCriteriaMet = aggregationIntervalMs < 1 || now - lastUpdatePublishedAt > aggregationIntervalMs

  private def schedule(tran: StreamState) = pendingState = Some(tran)


  def onNewState(canUpdate: () => Boolean, tran: StreamState, send: Any => Unit): Unit = {
    schedule(tran)
    publishPending(canUpdate, send)
  }

}


trait ConsumerPortActor extends Actor with DemandProducerContract with StreamDemandBinding with ConsumerDemandTracker {

  private val streamToBucket: mutable.Map[Subject, Bucket] = mutable.HashMap()
  private val priorityKeysToBuckets: mutable.Map[Option[String], PriorityBucketGroup] = mutable.HashMap()
  private val priorityGroups: util.ArrayList[PriorityBucketGroup] = new util.ArrayList[PriorityBucketGroup]()

  private var pendingMessages: List[Any] = List.empty

  //  private var pendingUnavailabilityUpdates: Set[ServiceKey] = Set.empty
  //  private var pendingInvalidRequestUpdates: List[Subject] = List.empty

  private val canUpdate = () => hasDemand && hasTarget

  private var pendingPublisherIdx = 0

  private case object SendPending

  var lastDemandRequestor: Option[ActorRef] = None

  def activeSubjects = streamToBucket.keys

  scheduleNextCheck()

  private def hasTarget = lastDemandRequestor isDefined

  private def scheduleNextCheck() = scheduleOnce(200 millis, SendPending)

  onMessage {
    case StreamStateUpdate(key, tran) =>
      onUpdate(key, tran)
    case SendPending =>
      publishPending()
      scheduleNextCheck()
  }

  def invalidRequest(subj: Subject) = {
    pendingMessages = pendingMessages :+ InvalidRequest(subj)
    processPendingMessages()
  }

  def serviceAvailable(service: ServiceKey) = pendingMessages = pendingMessages filter {
    case ServiceNotAvailable(key) => key != service
    case _ => true
  }

  def serviceUnavailable(service: ServiceKey) = if (!pendingMessages.exists {
    case ServiceNotAvailable(key) => key == service
    case _ => false
  }) pendingMessages = pendingMessages :+ ServiceNotAvailable(service)


  @tailrec private def processPendingMessages(): Unit = {
    if (pendingMessages.nonEmpty && canUpdate()) {
      send(pendingMessages.head)
      pendingMessages = pendingMessages.tail
      processPendingMessages()
    }
  }

  private def remove(pg: PriorityBucketGroup) = {
    priorityKeysToBuckets -= pg.priorityKey
    priorityGroups remove pg
  }

  def subjectClosed(subj: Subject) =  {
    pendingMessages = pendingMessages :+ SubscriptionClosed(subj)
    processPendingMessages()
  }


  def remove(subj: Subject) = {
    streamToBucket get subj foreach closeBucket
  }

  def add(subj: Subject, priorityKey: Option[String], aggregationIntervalMs: Int) = {
    streamToBucket get subj foreach closeBucket
    newBucket(subj, priorityKey, aggregationIntervalMs)
  }

  override def onConsumerDemand(sender: ActorRef, demand: Long): Unit = {
    if (!lastDemandRequestor.contains(sender)) lastDemandRequestor = Some(sender)
    logger.info(s"!>>> service port received demand request, current: $currentDemand, adding $demand")
    addConsumerDemand(demand)
    publishPending()
  }

  private def publishPending(): Unit = if (priorityGroups.size() > 0) {
    processPendingMessages()
    val cycles = priorityGroups.size()
    var cnt = 0
    while (cnt < cycles && hasDemand) {
      if (pendingPublisherIdx < 0 || pendingPublisherIdx >= priorityGroups.size()) pendingPublisherIdx = 0
      priorityGroups get pendingPublisherIdx publishPending(canUpdate, send)
      pendingPublisherIdx += 1
      cnt += 1
    }
  }

  private def closeBucket(bucket: Bucket): Unit = {
    priorityKeysToBuckets get bucket.priorityKey foreach { pg =>
      pg.remove(bucket)
      if (pg.isEmpty) remove(pg)
    }
    streamToBucket -= bucket.subj
  }

  private def newPriorityGroup(key: Option[String]) = {
    val group = new PriorityBucketGroup(key)
    priorityKeysToBuckets += key -> group
    priorityGroups.add(group)
    util.Collections.sort(priorityGroups)
    group
  }

  private def initialiseBucket(bucket: Bucket) = {
    priorityKeysToBuckets getOrElse(bucket.priorityKey, newPriorityGroup(bucket.priorityKey)) add bucket
  }

  private def newBucket(key: Subject, priorityKey: Option[String], aggregationIntervalMs: Int): Bucket = {
    val bucket = new Bucket(key, priorityKey, aggregationIntervalMs)
    streamToBucket += key -> bucket
    initialiseBucket(bucket)
    bucket
  }

  private def send(msg: Any) = fulfillDownstreamDemandWith {
    lastDemandRequestor foreach {
      ref =>
        logger.info(s"!>>>> Sending $msg to parent: $ref")
        ref ! msg
    }
  }

  private def onUpdate(key: Subject, tran: StreamState): Unit = {
    logger.info(s"!>>>> onUpdate : $key = $tran from ${sender()}")
    upstreamDemandFulfilled(sender(), 1)
    streamToBucket get key foreach { b =>
      logger.info("!>>>>> Ok, converted " + key)
      b.onNewState(canUpdate, tran, send)
    }
  }
}


class StreamAggregatorActor
  extends ActorWithComposableBehavior
  with ConsumerPortActor {


  private var serviceLocations: Map[ServiceKey, Option[ActorRef]] = Map.empty

  private def closeLocation(service: ServiceKey) =
    serviceLocations.get(service).flatten foreach { loc =>
      cancelDemandProducerFor(loc)
      loc ! CloseAllLocalStreams
    }

  private def openLocation(service: ServiceKey) =
    serviceLocations.get(service).flatten match {
      case Some(loc) =>
        startDemandProducerFor(loc, withAcknowledgedDelivery = false)
        serviceAvailable(service)
        loc ! OpenLocalStreamsForAll(activeSubjects.filter(_.service == service).toList)
      case None =>
        serviceUnavailable(service)
    }

  private def switchLocation(service: ServiceKey, location: Option[ActorRef]): Unit = {

    println(s"!>>>> switchLocation: $service -> $location")

    closeLocation(service)
    serviceLocations += service -> location
    openLocation(service)
  }


  onMessage {
    case InvalidRequest(subj) => invalidRequest(subj)
    case OpenSubscription(subj, prioKey, aggr) =>
      subjectClosed(subj)
      add(subj, prioKey, aggr)
      serviceLocations.get(subj.service).flatten foreach { loc =>
        loc ! OpenLocalStreamFor(subj)
      }
    case CloseSubscription(subj) =>
      subjectClosed(subj)
      remove(subj) //TODO check that upstream stream is closed
      serviceLocations.get(subj.service).flatten foreach { loc =>
        loc ! CloseLocalStreamFor(subj)
      }

    case ServiceLocationChanged(sKey, newLoc) => switchLocation(sKey, newLoc)

  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    serviceLocations.values.flatten foreach { loc =>
      loc ! CloseAllLocalStreams
    }
    super.postStop()
  }

  override def componentId: String = "StreamAggregatorActor"
}
