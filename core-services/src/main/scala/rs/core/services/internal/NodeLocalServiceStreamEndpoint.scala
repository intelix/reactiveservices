package rs.core.services.internal

import java.util

import akka.actor.{ActorRef, Props}
import akka.cluster.Cluster
import rs.core.actors.{ActorWithComposableBehavior, ActorWithTicks}
import rs.core.registry.RegistryRef
import rs.core.services.Messages.{InvalidRequest, StreamStateUpdate}
import rs.core.services.ServiceCell._
import rs.core.services.StreamId
import rs.core.services.internal.InternalMessages.StreamUpdate
import rs.core.services.internal.NodeLocalServiceStreamEndpoint._
import rs.core.services.internal.acks.Acknowledgeable
import rs.core.stream.{StreamState, StreamStateTransition}
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.core.{ServiceKey, Subject}

import scala.collection.mutable
import scala.language.postfixOps


trait NodeLocalServiceStreamEndpointSysevents extends ComponentWithBaseSysevents {

  val EndpointStarted = "EndpointStarted".info
  val EndpointStopped = "EndpointStopped".info

  val OpenedLocalStream = "OpenedLocalStream".trace
  val ClosedLocalStream = "ClosedLocalStream".trace

  val SubjectMappingRequested = "SubjectMappingRequested".trace
  val SubjectMappingReceived = "SubjectMappingReceived".trace

  val StreamUpdateReceived = "StreamUpdateReceived".trace
  val StreamResyncRequested = "StreamResyncRequest".warn
  val StreamUpdatedBroadcasted = "StreamUpdatedBroadcasted".trace

  val AcknowledgeableForwarded = "AcknowledgeableForwarded".info

  override def componentId: String = "NodeLocalStreamEndpoint"

}

object NodeLocalServiceStreamEndpoint {

  def remoteStreamAgentProps(serviceKey: ServiceKey, serviceRef: ActorRef) = Props(classOf[AgentActor], serviceKey, serviceRef)

  case class OpenLocalStreamFor(subject: Subject)

  case class CloseLocalStreamFor(subject: Subject)

  case class OpenLocalStreamsForAll(subjects: List[Subject])

  case object CloseAllLocalStreams

}


class NodeLocalServiceStreamEndpoint(serviceKey: ServiceKey, serviceRef: ActorRef)
  extends ActorWithComposableBehavior
  with StreamDemandBinding
  with DemandProducerContract
  with LocalStreamsBroadcaster
  with ActorWithTicks
  with RegistryRef
  with NodeLocalServiceStreamEndpointSysevents {

  private var pendingMappings: Map[Subject, Long] = Map.empty
  private var mappings: Map[Subject, Option[StreamId]] = Map.empty
  private var interests: Map[ActorRef, Set[Subject]] = Map.empty

  override def componentId: String = super.componentId + "." + serviceKey.id

  onTick {
    checkPendingMappings()
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

  override def onActiveStream(key: StreamId): Unit = {
    acknowledgedDelivery(key, OpenStreamFor(key), SpecificDestination(serviceRef), Some(_ => true))
  }

  override def shouldProcessAcknowledgeable(sender: ActorRef, m: Acknowledgeable): Boolean =
    if (sender == serviceRef) true else super.shouldProcessAcknowledgeable(sender, m)

  override def preStart(): Unit = {
    super.preStart()
    startDemandProducerFor(serviceRef, withAcknowledgedDelivery = true)
    registerService(serviceKey)
    EndpointStarted('service -> serviceKey, 'ref -> serviceRef)
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    unregisterService(serviceKey)
    super.postStop()
    EndpointStopped('service -> serviceKey, 'ref -> serviceRef)
  }

  override def onConsumerDemand(sender: ActorRef, demand: Long): Unit = newConsumerDemand(sender, demand)

  private def openLocalStream(subscriber: ActorRef, subj: Subject): Unit = OpenedLocalStream { ctx =>
    ctx +('subj -> subj, 'subscriber -> subscriber)
    interests += subscriber -> (interests.getOrElse(subscriber, Set.empty) + subj)
    mappings get subj match {
      case Some(Some(key)) =>
        initiateStreamFor(subscriber, key, subj)
        ctx + ('info -> "Active stream")
      case Some(None) =>
        publishNotAvailable(subscriber, subj)
        ctx + ('info -> "Stream not available")
      case None =>
        requestMapping(subj)
        ctx + ('info -> "Requested mapping")
    }
  }

  private def closeLocalStream(subscriber: ActorRef, subj: Subject): Unit = ClosedLocalStream { ctx =>
    ctx +('subj -> subj, 'subscriber -> subscriber)
    interests.get(subscriber) foreach { currentInterestsForSubscriber =>
      val remainingInterests = currentInterestsForSubscriber - subj
      closeStreamFor(subscriber, subj)
      if (remainingInterests.isEmpty) {
        context.unwatch(subscriber)
        interests -= subscriber
        ctx + ('subscribers -> 0)
      } else {
        interests += subscriber -> remainingInterests
        val remaining = remainingInterests.size
        ctx + ('subscribers -> remaining)
      }
    }
  }

  private def checkPendingMappings() = {
    if (pendingMappings.nonEmpty) {
      pendingMappings collect {
        case (subj, sent) if now - sent > 5000 => subj
      } foreach requestMapping
    }
  }

  private def requestMapping(subj: Subject): Unit = SubjectMappingRequested { ctx =>
    serviceRef ! GetMappingFor(subj)
    pendingMappings += subj -> now
    ctx +('subj -> subj, 'pending -> pendingMappings.size)
  }

  private def publishNotAvailable(subscriber: ActorRef, subj: Subject): Unit = subscriber ! InvalidRequest(subj)


  onMessage {
    case m: Acknowledgeable =>
      serviceRef forward m
      AcknowledgeableForwarded('id -> m.messageId)
    case OpenLocalStreamFor(subj) => openLocalStream(sender(), subj)
    case OpenLocalStreamsForAll(list) => list foreach { subj => openLocalStream(sender(), subj) }
    case CloseLocalStreamFor(subj) => closeLocalStream(sender(), subj)
    case CloseAllLocalStreams => closeAllFor(sender())
    case StreamMapping(subj, maybeKey) => onReceivedStreamMapping(subj, maybeKey)
    case StreamUpdate(key, tran) => updateLocalStream(key, tran)
  }

  private def publishNotAvailable(subj: Subject): Unit = interests foreach {
    case (ref, set) if set.contains(subj) => publishNotAvailable(ref, subj)
  }

  private def onReceivedStreamMapping(subj: Subject, maybeKey: Option[StreamId]): Unit = SubjectMappingReceived { ctx =>
    ctx +('subj -> subj, 'stream -> maybeKey)
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

  private def updateLocalStream(key: StreamId, tran: StreamStateTransition): Unit = StreamUpdateReceived { ctx =>
    ctx +('stream -> key, 'payload -> tran)
    upstreamDemandFulfilled(serviceRef, 1)
    if (!onStateTransition(key, tran)) {
      serviceRef ! StreamResyncRequest(key)
      StreamResyncRequested('stream -> key)
    }
  }

  private def closeAllFor(ref: ActorRef): Unit = {
    interests get ref foreach { set => set.foreach { subj => closeLocalStream(sender(), subj) } }
    terminateTarget(ref)
  }


  onActorTerminated { ref =>
    closeAllFor(ref)
  }


}


private class LocalSubjectStreamSink(val streamKey: StreamId, subj: Subject, canUpdate: () => Boolean, updateDownstream: StreamState => Unit) {

  private var pendingState: Option[StreamState] = None
  private var remoteView: Option[StreamState] = None

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


private class LocalTargetWithSinks(ref: ActorRef, self: ActorRef) extends ConsumerDemandTracker with WithSyseventPublisher with NodeLocalServiceStreamEndpointSysevents {
  private val subjectToSink: mutable.Map[Subject, LocalSubjectStreamSink] = mutable.HashMap()
  private val streams: util.ArrayList[LocalSubjectStreamSink] = new util.ArrayList[LocalSubjectStreamSink]()
  private val canUpdate = () => hasDemand
  private var nextPublishIdx = 0

  def isIdle = streams.isEmpty

  def locateExistingSinkFor(key: Subject): Option[LocalSubjectStreamSink] = subjectToSink get key

  def allSinks = subjectToSink.values

  def closeStream(subj: Subject) = {
    subjectToSink get subj foreach { existingSink =>
      subjectToSink -= subj
      streams remove existingSink
    }
  }

  def addStream(key: StreamId, subj: Subject): LocalSubjectStreamSink = {
    closeStream(subj)
    val newSink = new LocalSubjectStreamSink(key, subj, canUpdate, updateForTarget(subj))
    subjectToSink += subj -> newSink
    streams add newSink
    newSink
  }

  def addDemand(demand: Long): Unit = {
    addConsumerDemand(demand)
    publishToAll()
  }


  private def updateForTarget(subj: Subject)(state: StreamState) = fulfillDownstreamDemandWith {
    ref.tell(StreamStateUpdate(subj, state), self)
    StreamUpdatedBroadcasted('subj -> subj)
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


private class LocalStreamBroadcaster {

  private val sinks: util.ArrayList[LocalSubjectStreamSink] = new util.ArrayList[LocalSubjectStreamSink]()
  private var latestState: Option[StreamState] = None

  def state = latestState

  // TODO introduce cooling down period
  def isIdle = sinks.isEmpty

  def removeLocalSink(existingSink: LocalSubjectStreamSink) = sinks remove existingSink

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
    latestState foreach { state => streamSink.onNewState(state) }
  }


}


private trait LocalStreamsBroadcaster extends ActorWithComposableBehavior with ActorWithTicks {

  private val targets: mutable.Map[ActorRef, LocalTargetWithSinks] = mutable.HashMap()
  private val streams: mutable.Map[StreamId, LocalStreamBroadcaster] = mutable.HashMap()

  def newConsumerDemand(consumer: ActorRef, demand: Long): Unit = targets get consumer foreach (_.addDemand(demand))

  final def onStateUpdate(subj: StreamId, state: StreamState) = {
    streams get subj foreach (_.onNewState(state))
  }

  final def onStateTransition(subj: StreamId, state: StreamStateTransition) = {
    streams get subj forall (_.onStateTransition(state))
  }

  def initiateStreamFor(ref: ActorRef, key: StreamId, subj: Subject) = {
    closeStreamFor(ref, subj)
    val target = targets getOrElse(ref, newTarget(ref))
    val stream = streams getOrElse(key, newStreamBroadcaster(key))
    val newSink = target.addStream(key, subj)
    stream.addLocalSink(newSink)
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


  override def processTick(): Unit = {
    streams.collect {
      case (s, v) if v.isIdle => s
    } foreach { key =>
      streams.remove(key)
      onIdleStream(key)
    }
    super.processTick()
  }

  private def newStreamBroadcaster(streamKey: StreamId) = {
    val sb = new LocalStreamBroadcaster()
    streams += streamKey -> sb
    onActiveStream(streamKey)
    sb
  }

  private def newTarget(ref: ActorRef) = {
    val target = new LocalTargetWithSinks(ref, self)
    targets += ref -> target
    target
  }

}


trait NodeLocalServiceAgentSysevents extends ComponentWithBaseSysevents {

  val AgentStarted = "AgentStarted".info
  val AgentStopped = "AgentStopped".warn

  override def componentId: String = "NodeLocalServiceAgent"

}

object AgentActor {
  private def localStreamLinkProps(serviceKey: ServiceKey, serviceRef: ActorRef) = Props(classOf[NodeLocalServiceStreamEndpoint], serviceKey, serviceRef)
}

class AgentActor(serviceKey: ServiceKey, serviceRef: ActorRef)
  extends ActorWithComposableBehavior
  with MessageAcknowledging
  with SimpleInMemoryAcknowledgedDelivery
  with NodeLocalServiceAgentSysevents {

  val actor = context.system.actorOf(AgentActor.localStreamLinkProps(serviceKey, serviceRef))


  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('service -> serviceKey, 'ref -> serviceRef)

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    val cluster = Cluster.get(context.system)
    acknowledgedDelivery(serviceRef, ServiceEndpoint(actor, cluster.selfAddress), SpecificDestination(serviceRef))
    AgentStarted()
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    super.postStop()
    AgentStopped()
    context.system.stop(actor)
  }

}


