/*
 * Copyright 2014-16 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rs.core.services.internal

import java.util

import akka.actor.{ActorRef, Props}
import rs.core.actors.StatelessActor
import rs.core.evt.{EvtSource, TraceE}
import rs.core.services.Messages._
import rs.core.services.internal.NodeLocalServiceStreamEndpoint._
import rs.core.stream.StreamState
import rs.core.utils.NowProvider
import rs.core.{ServiceKey, Subject}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps


object StreamAggregatorActor {
  val EvtSourceId = "StreamAggregator"

  case object EvtSubjectUpdateReceived extends TraceE

  case object EvtDownstreamConsumer extends TraceE

  case object EvtSentDownstream extends TraceE

  case object EvtServiceLocationUpdated extends TraceE

  def props(consumerId: String) = Props(classOf[StreamAggregatorActor], consumerId)

  case class ServiceLocationChanged(serviceKey: ServiceKey, location: Option[ActorRef])

}


final class StreamAggregatorActor(consumerId: String)
  extends StatelessActor with DemandProducerContract with StreamDemandBinding with ConsumerDemandTracker {

  import StreamAggregatorActor._

  private val streamToBucket: mutable.Map[Subject, Bucket] = mutable.HashMap()
  private val priorityKeysToBuckets: mutable.Map[Option[String], PriorityBucketGroup] = mutable.HashMap()
  private val priorityGroups: util.ArrayList[PriorityBucketGroup] = new util.ArrayList[PriorityBucketGroup]()
  private val canUpdate = () => hasDemand && hasTarget

  private var lastDemandRequestor: Option[ActorRef] = None
  private var pendingMessages: List[Any] = List.empty
  private var pendingPublisherIdx = 0
  private var serviceLocations: Map[ServiceKey, Option[ActorRef]] = Map.empty

  addEvtFields('consumer -> consumerId)

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    scheduleNextCheck()
  }

  private def scheduleNextCheck() = scheduleOnce(200 millis, SendPending)

  def invalidRequest(subj: Subject) = {
    pendingMessages = pendingMessages :+ InvalidRequest(subj)
    processPendingMessages()
  }

  def subjectClosed(subj: Subject) = {
    pendingMessages = pendingMessages :+ SubscriptionClosed(subj)
    processPendingMessages()
  }

  onMessage {
    case StreamStateUpdate(key, tran) =>
      onUpdate(key, tran)
    case SendPending =>
      publishPending()
      scheduleNextCheck()
  }

  def remove(subj: Subject) = {
    streamToBucket get subj foreach closeBucket
  }

  private def closeBucket(bucket: Bucket): Unit = {
    priorityKeysToBuckets get bucket.priorityKey foreach { pg =>
      pg.remove(bucket)
      if (pg.isEmpty) remove(pg)
    }
    streamToBucket -= bucket.subj
  }

  private def remove(pg: PriorityBucketGroup) = {
    priorityKeysToBuckets -= pg.priorityKey
    priorityGroups remove pg
  }

  def add(subj: Subject, priorityKey: Option[String], aggregationIntervalMs: Int) = {
    streamToBucket get subj foreach closeBucket
    newBucket(subj, priorityKey, aggregationIntervalMs)
  }

  private def newBucket(key: Subject, priorityKey: Option[String], aggregationIntervalMs: Int): Unit = {
    val bucket = new Bucket(key, priorityKey, aggregationIntervalMs)
    streamToBucket += key -> bucket
    initialiseBucket(bucket)
  }

  private def initialiseBucket(bucket: Bucket) = {
    priorityKeysToBuckets getOrElse(bucket.priorityKey, newPriorityGroup(bucket.priorityKey)) add bucket
  }

  private def newPriorityGroup(key: Option[String]) = {
    val group = new PriorityBucketGroup(key)
    priorityKeysToBuckets += key -> group
    priorityGroups.add(group)
    util.Collections.sort(priorityGroups)
    group
  }

  override def onConsumerDemand(sender: ActorRef, demand: Long): Unit = {
    if (!lastDemandRequestor.contains(sender)) {
      raise(EvtDownstreamConsumer, 'ref -> sender)
      lastDemandRequestor = Some(sender)
    }
    addConsumerDemand(demand)
    publishPending()
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    serviceLocations.values.flatten foreach { loc =>
      loc ! CloseAllLocalStreams
    }
    super.postStop()
  }

  private def onUpdate(key: Subject, tran: StreamState): Unit = {
    raise(EvtSubjectUpdateReceived, 'subj -> key, 'payload -> tran)
    upstreamDemandFulfilled(sender(), 1)
    streamToBucket get key foreach { b =>
      b.onNewState(canUpdate, tran, send)
    }
  }

  private def send(msg: Any) = {
    fulfillDownstreamDemandWith {
      lastDemandRequestor foreach {
        ref =>
          ref ! msg
          raise(EvtSentDownstream, 'ref -> ref, 'payload -> msg)
      }
    }
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

  private def hasTarget = lastDemandRequestor isDefined

  @tailrec private def processPendingMessages(): Unit = {
    if (pendingMessages.nonEmpty && canUpdate()) {
      send(pendingMessages.head)
      pendingMessages = pendingMessages.tail
      processPendingMessages()
    }
  }

  private def switchLocation(service: ServiceKey, location: Option[ActorRef]): Unit = {
    closeLocation(service)
    serviceLocations += service -> location
    openLocation(service)
    raise(EvtServiceLocationUpdated, 'service -> service, 'ref -> location)
  }

  private def closeLocation(service: ServiceKey) =
    serviceLocations.get(service).flatten foreach { loc =>
      cancelDemandProducerFor(loc)
      loc ! CloseAllLocalStreams
    }

  private def openLocation(service: ServiceKey) =
    serviceLocations.get(service).flatten match {
      case Some(loc) =>
        loc ! OpenLocalStreamsForAll(activeSubjects.filter(_.service == service).toList)
        startDemandProducerFor(loc, withAcknowledgedDelivery = false)
        serviceAvailable(service)
      case None =>
        serviceUnavailable(service)
    }

  def activeSubjects = streamToBucket.keys

  def serviceAvailable(service: ServiceKey) = pendingMessages = pendingMessages filter {
    case ServiceNotAvailable(key) => key != service
    case _ => true
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
      remove(subj) //TODO check that upstream stream is closed.    7 Dec: Review if still needs to be done?
      serviceLocations.get(subj.service).flatten foreach { loc =>
        loc ! CloseLocalStreamFor(subj)
      }

    case ServiceLocationChanged(sKey, newLoc) => switchLocation(sKey, newLoc)

  }

  def serviceUnavailable(service: ServiceKey) = if (!pendingMessages.exists {
    case ServiceNotAvailable(key) => key == service
    case _ => false
  }) pendingMessages = pendingMessages :+ ServiceNotAvailable(service)

  private case object SendPending

  override val evtSource: EvtSource = EvtSourceId
}


private class PriorityBucketGroup(val priorityKey: Option[String]) extends Comparable[PriorityBucketGroup] {
  private lazy val preCalculatedHashCode = priorityKey.hashCode()
  private val buckets: util.ArrayList[Bucket] = new util.ArrayList[Bucket]()
  private var idx = 0

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

  override def equals(other: Any): Boolean = other match {
    case that: PriorityBucketGroup =>
      (that canEqual this) &&
        priorityKey == that.priorityKey
    case _ => false
  }

  //noinspection ComparingUnrelatedTypes
  def canEqual(other: Any): Boolean = other.isInstanceOf[PriorityBucketGroup]

  override def hashCode(): Int = preCalculatedHashCode

  override def compareTo(o: PriorityBucketGroup): Int = priorityKey match {
    case x if x == o.priorityKey => 0
    case Some(x) if o.priorityKey.exists(_ < x) => 1
    case _ => -1
  }
}

private class Bucket(val subj: Subject, val priorityKey: Option[String], aggregationIntervalMs: Int) {

  import NowProvider._

  var lastUpdatePublishedAt = 0L
  private var pendingState: Option[StreamState] = None
  private var state: Option[StreamState] = None

  def onNewState(canUpdate: () => Boolean, tran: StreamState, send: Any => Unit): Unit = {
    schedule(tran)
    publishPending(canUpdate, send)
  }

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

}

