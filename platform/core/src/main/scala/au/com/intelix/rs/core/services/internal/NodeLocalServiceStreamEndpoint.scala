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
package au.com.intelix.rs.core.services.internal

import java.util

import akka.actor.{ActorRef, Props}
import akka.cluster.Cluster
import au.com.intelix.essentials.time.NowProvider
import com.typesafe.config._
import au.com.intelix.rs.core.actors.{ActorWithTicks, StatelessActor}
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.evt.{EvtSource, InfoE, TraceE, WarningE}
import au.com.intelix.rs.core.config.ServiceConfig
import au.com.intelix.rs.core.registry.RegistryRef
import au.com.intelix.rs.core.services.BaseServiceActor._
import au.com.intelix.rs.core.services.Messages.{InvalidRequest, StreamStateUpdate}
import au.com.intelix.rs.core.services.StreamId
import au.com.intelix.rs.core.services.internal.InternalMessages.StreamUpdate
import au.com.intelix.rs.core.services.internal.acks.Acknowledgeable
import au.com.intelix.rs.core.stream.{StreamState, StreamStateTransition}
import au.com.intelix.rs.core.{ServiceKey, Subject}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps


object NodeLocalServiceStreamEndpoint {

  object Evt {
    case object EndpointStarted extends InfoE
    case object EndpointStopped extends InfoE
    case object RejectedLocalStream extends TraceE
    case object OpenedLocalStream extends TraceE
    case object ReusedLocalStream extends TraceE
    case object ClosedLocalStream extends TraceE
    case object SubjectMappingRequested extends TraceE
    case object SubjectMappingReceived extends TraceE
    case object StreamUpdateReceived extends TraceE
    case object StreamResyncRequested extends InfoE
    case object AcknowledgeableForwarded extends TraceE
  }


  def remoteStreamAgentProps(serviceKey: ServiceKey, serviceRef: ActorRef, instanceId: String) = Props(classOf[ServiceAgentActor], serviceKey, serviceRef, instanceId)

  case class OpenLocalStreamFor(subject: Subject)

  case class CloseLocalStreamFor(subject: Subject)

  case class OpenLocalStreamsForAll(subjects: List[Subject])

  case object CloseAllLocalStreams

}


class NodeLocalServiceStreamEndpoint(override val serviceKey: ServiceKey, serviceRef: ActorRef)
  extends StatelessActor
  with StreamDemandBinding
  with DemandProducerContract
  with LocalStreamsBroadcaster
  with ActorWithTicks
  with RegistryRef {

  import NodeLocalServiceStreamEndpoint._

  implicit lazy val serviceCfg = ServiceConfig(config.asConfig(serviceKey.id))
  override val idleThreshold: FiniteDuration = serviceCfg.asFiniteDuration("idle-stream-threshold", 10 seconds)
  private var pendingMappings: Map[Subject, Long] = Map.empty
  private var mappings: Map[Subject, Option[StreamId]] = Map.empty

  private var interests: Map[ActorRef, Set[Subject]] = Map.empty

  override def preStart(): Unit = {
    super.preStart()
    startDemandProducerFor(serviceRef, withAcknowledgedDelivery = true)
    registerService(serviceKey)
    raise(Evt.EndpointStarted, 'service -> serviceKey, 'ref -> serviceRef)
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    unregisterService(serviceKey)
    super.postStop()
    raise(Evt.EndpointStopped, 'service -> serviceKey, 'ref -> serviceRef)
  }

  override def onIdleStream(key: StreamId): Unit = {
    acknowledgedDelivery(key, CloseStreamFor(key), SpecificDestination(serviceRef), Some(_ => true))
    mappings = mappings filter {
      case (k, Some(v)) if v == key =>
        pendingMappings -= k
        false
      case _ => true
    }
  }

  override def onActiveStream(key: StreamId): Unit =
    acknowledgedDelivery(key, OpenStreamFor(key), SpecificDestination(serviceRef), Some(_ => true))


  override def shouldProcessAcknowledgeable(sender: ActorRef, m: Acknowledgeable): Boolean =
    if (sender == serviceRef) true else super.shouldProcessAcknowledgeable(sender, m)

  override def onConsumerDemand(sender: ActorRef, demand: Long): Unit = newConsumerDemand(sender, demand)

  onTick {
    checkPendingMappings()
  }

  onMessage {
    case m: Acknowledgeable =>
      serviceRef forward m
      raise(Evt.AcknowledgeableForwarded, 'id -> m.messageId)
    case OpenLocalStreamFor(subj) => openLocalStream(sender(), subj)
    case OpenLocalStreamsForAll(list) => list foreach { subj => openLocalStream(sender(), subj) }
    case CloseLocalStreamFor(subj) => closeLocalStream(sender(), subj)
    case CloseAllLocalStreams => closeAllFor(sender())
    case StreamMapping(subj, maybeKey) => onReceivedStreamMapping(subj, maybeKey)
    case StreamUpdate(key, tran) => updateLocalStream(key, tran)
  }

  onActorTerminated { ref =>
    closeAllFor(ref)
  }


  private def openLocalStream(subscriber: ActorRef, subj: Subject): Unit = {
    interests += subscriber -> (interests.getOrElse(subscriber, Set.empty) + subj)
    mappings get subj match {
      case Some(Some(key)) =>
        initiateStreamFor(subscriber, key, subj)
        raise(Evt.OpenedLocalStream, 'subj -> subj, 'subscriber -> subscriber)
      case Some(None) =>
        publishNotAvailable(subscriber, subj)
        raise(Evt.RejectedLocalStream, 'subj -> subj, 'subscriber -> subscriber)
      case None =>
        initiateConsumer(subscriber)
        requestMapping(subj)
        raise(Evt.ReusedLocalStream, 'subj -> subj, 'subscriber -> subscriber)
    }
  }

  private def checkPendingMappings() = {
    if (pendingMappings.nonEmpty) {
      pendingMappings collect {
        case (subj, sent) if now - sent > 5000 => subj
      } foreach requestMapping
    }
  }

  private def requestMapping(subj: Subject): Unit = {
    serviceRef ! GetMappingFor(subj)
    pendingMappings += subj -> now
    raise(Evt.SubjectMappingRequested, 'subj -> subj, 'pending -> pendingMappings.size)
  }

  private def onReceivedStreamMapping(subj: Subject, maybeKey: Option[StreamId]): Unit = {
    raise(Evt.SubjectMappingReceived, 'subj -> subj, 'stream -> maybeKey)
    pendingMappings -= subj
    mappings get subj match {
      case Some(x) if x == maybeKey =>
      case Some(Some(x)) =>
        addStreamMapping(subj, maybeKey)
      case _ =>
        addStreamMapping(subj, maybeKey)
    }
  }

  private def addStreamMapping(subj: Subject, maybeKey: Option[StreamId]) = {
    interests foreach {
      case (ref, v) => if (v.contains(subj)) closeStreamFor(ref, subj)
    }
    mappings += subj -> maybeKey
    maybeKey match {
      case Some(k) =>
        interests foreach {
          case (ref, v) => if (v.contains(subj)) {
            initiateStreamFor(ref, k, subj)
          }
        }
      case None => publishNotAvailable(subj)
    }
  }

  private def publishNotAvailable(subj: Subject): Unit = interests foreach {
    case (ref, set) if set.contains(subj) => publishNotAvailable(ref, subj)
    case _ =>
  }

  private def publishNotAvailable(subscriber: ActorRef, subj: Subject): Unit = subscriber ! InvalidRequest(subj)

  private def updateLocalStream(key: StreamId, tran: StreamStateTransition): Unit = {
    raise(Evt.StreamUpdateReceived, 'stream -> key, 'payload -> tran)
    upstreamDemandFulfilled(serviceRef, 1)
    if (!onStateTransition(key, tran)) {
      serviceRef ! StreamResyncRequest(key)
      raise(Evt.StreamResyncRequested, 'stream -> key)
    }
  }

  private def closeAllFor(ref: ActorRef): Unit = {
    interests get ref foreach { set => set.foreach { subj => closeLocalStream(sender(), subj) } }
    terminateTarget(ref)
  }


  private def closeLocalStream(subscriber: ActorRef, subj: Subject): Unit = {
    interests.get(subscriber) foreach { currentInterestsForSubscriber =>
      val remainingInterests = currentInterestsForSubscriber - subj
      closeStreamFor(subscriber, subj)
      if (remainingInterests.isEmpty) {
        context.unwatch(subscriber)
        interests -= subscriber
      } else {
        interests += subscriber -> remainingInterests
        val remaining = remainingInterests.size
      }
      raise(Evt.ClosedLocalStream, 'subj -> subj, 'subscriber -> subscriber, 'subscribers -> remainingInterests.size)
    }
  }

}


private class LocalSubjectStreamSink(val streamKey: StreamId, subj: Subject, canUpdate: () => Boolean, updateDownstream: StreamState => Unit) {

  private var pendingState: Option[StreamState] = None
  private var remoteView: Option[StreamState] = None // TODO review

  def resetDownstreamView() = remoteView = None

  def onNewState(state: StreamState) = {
    if (canUpdate()) {
      updateDownstream(state)
      pendingState = None
    } else
      pendingState = Some(state)
  }

  def publishPending() =
    if (canUpdate()) {
      pendingState foreach updateDownstream
      pendingState = None
    }

}


private class LocalStreamBroadcaster(timeout: FiniteDuration) extends NowProvider {

  private val idleThreshold = timeout.toMillis
  private val sinks: util.ArrayList[LocalSubjectStreamSink] = new util.ArrayList[LocalSubjectStreamSink]()
  private var latestState: Option[StreamState] = None
  private var idleSince: Option[Long] = None

  def state = latestState

  def isIdle = idleSince match {
    case None => false
    case Some(time) => idleThreshold < 1 || now - time > idleThreshold
  }

  def removeLocalSink(existingSink: LocalSubjectStreamSink) = {
    if (sinks.remove(existingSink) && sinks.isEmpty) idleSince = Some(now)
  }

  def onStateTransition(state: StreamStateTransition): Boolean = {
    state.toNewStateFrom(latestState) match {
      case Some(newState) => onNewState(newState)
        true
      case _ => false
    }
  }

  def onNewState(state: StreamState) = {
    latestState = Some(state)
    var idx = 0
    while (idx < sinks.size()) {
      sinks.get(idx).onNewState(state)
      idx += 1
    }
  }

  def addLocalSink(streamSink: LocalSubjectStreamSink) = {
    sinks add streamSink
    if (idleSince isDefined) idleSince = None
    latestState foreach { state => streamSink.onNewState(state) }
  }


}


trait LocalStreamsBroadcaster extends StatelessActor with ActorWithTicks {

  val serviceKey: ServiceKey
  private val targets: mutable.Map[ActorRef, LocalTargetWithSinks] = mutable.HashMap()
  private val streams: mutable.Map[StreamId, LocalStreamBroadcaster] = mutable.HashMap()

  def idleThreshold: FiniteDuration

  def newConsumerDemand(consumer: ActorRef, demand: Long): Unit = {
    (targets getOrElse(consumer, newTarget(consumer))).addDemand(demand)
  }

  final def onStateUpdate(subj: StreamId, state: StreamState) = {
    streams get subj foreach (_.onNewState(state))
  }

  final def onStateTransition(subj: StreamId, state: StreamStateTransition) = {
    streams get subj forall (_.onStateTransition(state))
  }

  def initiateConsumer(ref: ActorRef): Unit = targets getOrElse(ref, newTarget(ref))

  def initiateStreamFor(ref: ActorRef, key: StreamId, subj: Subject) = {
    closeStreamFor(ref, subj)
    val target = targets getOrElse(ref, newTarget(ref))
    val stream = streams getOrElse(key, newStreamBroadcaster(key))
    val newSink = target.addStream(key, subj)
    stream.addLocalSink(newSink)
  }

  def closeStreamFor(ref: ActorRef, subj: Subject) = {
    targets get ref foreach { target =>
      target locateExistingSinkFor subj foreach { existingSink =>
        target.closeStream(subj)
        streams get existingSink.streamKey foreach { stream =>
          stream removeLocalSink existingSink
        }
      }
    }
  }

  private def newStreamBroadcaster(streamKey: StreamId) = {
    val sb = new LocalStreamBroadcaster(idleThreshold)
    streams += streamKey -> sb
    onActiveStream(streamKey)
    sb
  }

  private def newTarget(ref: ActorRef) = {
    val target = new LocalTargetWithSinks(ref, self, serviceKey.id)
    targets += ref -> target
    target
  }

  def terminateTarget(ref: ActorRef) = {
    targets.get(ref) foreach { l =>
      l.allSinks.foreach { sink =>
        streams get sink.streamKey foreach { stream =>
          stream removeLocalSink sink
        }
      }
      targets -= ref
    }

  }

  def onIdleStream(key: StreamId)

  def onActiveStream(key: StreamId)

  onTick {
    streams.collect {
      case (s, v) if v.isIdle => s
    } foreach { key =>
      streams.remove(key)
      onIdleStream(key)
    }
  }


  private class LocalTargetWithSinks(ref: ActorRef, self: ActorRef, serviceId: String)(implicit val config: Config) extends ConsumerDemandTracker {
    private val subjectToSink: mutable.Map[Subject, LocalSubjectStreamSink] = mutable.HashMap()
    private val streams: util.ArrayList[LocalSubjectStreamSink] = new util.ArrayList[LocalSubjectStreamSink]()
    private val canUpdate = () => hasDemand
    private var nextPublishIdx = 0

    def isIdle = streams.isEmpty

    def locateExistingSinkFor(key: Subject): Option[LocalSubjectStreamSink] = subjectToSink get key

    def allSinks = subjectToSink.values

    def addStream(key: StreamId, subj: Subject): LocalSubjectStreamSink = {
      closeStream(subj)
      val newSink = new LocalSubjectStreamSink(key, subj, canUpdate, updateForTarget(subj))
      subjectToSink += subj -> newSink
      streams add newSink
      newSink
    }

    def closeStream(subj: Subject) = {
      subjectToSink get subj foreach { existingSink =>
        subjectToSink -= subj
        streams remove existingSink
      }
    }

    def addDemand(demand: Long): Unit = {
      addConsumerDemand(demand)
      publishToAll()
    }

    private def updateForTarget(subj: Subject)(state: StreamState) = fulfillDownstreamDemandWith {
      ref.tell(StreamStateUpdate(subj, state), self)
    }

    private def publishToAll() = if (streams.size() > 0) {
      val cycles = streams.size()
      var cnt = 0
      while (cnt < cycles && hasDemand) {
        streams get nextPublishIdx publishPending()
        nextPublishIdx += 1
        if (nextPublishIdx == streams.size()) nextPublishIdx = 0
        cnt += 1
      }
    }

  }


}


object ServiceAgentActor {
  object Evt {
    case object AgentStarted extends InfoE
    case object AgentStopped extends WarningE
  }

  private def localStreamLinkProps(serviceKey: ServiceKey, serviceRef: ActorRef) = Props(classOf[NodeLocalServiceStreamEndpoint], serviceKey, serviceRef)
}

class ServiceAgentActor(serviceKey: ServiceKey, serviceRef: ActorRef, instanceId: String)
  extends StatelessActor
  with MessageAcknowledging
  with SimpleInMemoryAcknowledgedDelivery {

  import ServiceAgentActor._

  var actor: Option[ActorRef] = None


  commonEvtFields('service -> serviceKey, 'ref -> serviceRef)

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    actor = Some(context.system.actorOf(ServiceAgentActor.localStreamLinkProps(serviceKey, serviceRef), s"$serviceKey-$instanceId"))
    val cluster = Cluster.get(context.system)
    acknowledgedDelivery(serviceRef, ServiceEndpoint(actor.get, cluster.selfAddress.toString), SpecificDestination(serviceRef))
    raise(Evt.AgentStarted, 'proxy -> actor)
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    super.postStop()
    raise(Evt.AgentStopped)
    actor.foreach(context.system.stop)
  }

}


