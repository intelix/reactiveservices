package rs.core.services.internal

import java.util

import akka.actor.{ActorRef, Props}
import akka.cluster.Cluster
import rs.core.actors.{ActorWithComposableBehavior, ActorWithTicks}
import rs.core.registry.RegistryRef
import rs.core.services.Messages.{InvalidRequest, StreamStateUpdate}
import rs.core.services.ServiceCell._
import rs.core.services.internal.InternalMessages.StreamUpdate
import rs.core.services.internal.ServiceStreamingEndpointActor._
import rs.core.services.internal.acks.{Acknowledgeable, SimpleInMemoryAcknowledgedDelivery}
import rs.core.stream.{StreamState, StreamStateTransition}
import rs.core.{ServiceKey, Subject}

import scala.collection.mutable
import scala.language.postfixOps

object ServiceStreamingEndpointActor {

  case class OpenLocalStreamFor(subject: Subject)

  case class CloseLocalStreamFor(subject: Subject)

  case object CloseAllLocalStreams

  case class OpenLocalStreamsForAll(subjects: List[Subject])


  def props(serviceKey: ServiceKey, serviceRef: ActorRef) = Props(classOf[AgentActor], serviceKey, serviceRef)

  def props2(serviceKey: ServiceKey, serviceRef: ActorRef) = Props(classOf[ServiceStreamingEndpointActor], serviceKey, serviceRef)
}


class LocalSubjectStreamSink(val streamKey: StreamRef, subj: Subject, canUpdate: () => Boolean, updateDownstream: StreamState => Unit) {

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


class LocalTargetWithSinks(ref: ActorRef, self: ActorRef) extends ConsumerDemandTracker {
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

  def addStream(key: StreamRef, subj: Subject): LocalSubjectStreamSink = {
    closeStream(subj)
    val newSink = new LocalSubjectStreamSink(key, subj, canUpdate, updateForTarget(subj))
    subjectToSink += subj -> newSink
    streams add newSink
    newSink
  }

  def addDemand(demand: Long): Unit = {
    println(s"!>>> new demand for $ref, was: $currentDemand adding $demand")
    addConsumerDemand(demand)
    publishToAll()
  }

  private def updateForTarget(subj: Subject)(state: StreamState) = fulfillDownstreamDemandWith {
    println(s"!>>> updateForTarget $subj -> $ref : " + state)
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


class LocalStreamBroadcaster {

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


trait LocalStreamsBroadcaster extends ActorWithComposableBehavior with ActorWithTicks {

  private val targets: mutable.Map[ActorRef, LocalTargetWithSinks] = mutable.HashMap()
  private val streams: mutable.Map[StreamRef, LocalStreamBroadcaster] = mutable.HashMap()

  def newConsumerDemand(consumer: ActorRef, demand: Long): Unit = targets get consumer foreach (_.addDemand(demand))

  final def onStateUpdate(subj: StreamRef, state: StreamState) = {
    streams get subj foreach (_.onNewState(state))
  }

  final def onStateTransition(subj: StreamRef, state: StreamStateTransition) = {
    streams get subj forall (_.onStateTransition(state))
  }

  def initiateStreamFor(ref: ActorRef, key: StreamRef, subj: Subject) = {
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

  def onIdleStream(key: StreamRef)

  def onActiveStream(key: StreamRef)


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

  private def newStreamBroadcaster(streamKey: StreamRef) = {
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

class AgentActor(serviceKey: ServiceKey, serviceRef: ActorRef)
  extends ActorWithComposableBehavior with MessageAcknowledging with SimpleInMemoryAcknowledgedDelivery {

  val actor = context.system.actorOf(ServiceStreamingEndpointActor.props2(serviceKey, serviceRef)) // TODO name?
  val cluster = Cluster.get(context.system)
  acknowledgedDelivery(serviceRef, ServiceEndpoint(actor, cluster.selfAddress), SpecificDestination(serviceRef))

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    super.postStop()
    logger.info("!>>>> Agent stopped")
    context.system.stop(actor)
  }

  override def componentId: String = "Agent"
}


class ServiceStreamingEndpointActor(serviceKey: ServiceKey, serviceRef: ActorRef)
  extends ActorWithComposableBehavior
  with StreamDemandBinding
  with DemandProducerContract
  with LocalStreamsBroadcaster
  with ActorWithTicks
  with RegistryRef {


  override def componentId: String = "ServiceRemoteAgentActor"

  private var pendingMappings: Map[Subject, Long] = Map.empty
  private var mappings: Map[Subject, Option[StreamRef]] = Map.empty
  private var interests: Map[ActorRef, Set[Subject]] = Map.empty


  // TODO test that if parent dies this actor dies too - pretty sure it does, but if not, need to watch the parent


  override def processTick(): Unit = {
    checkPendingMappings()
    super.processTick()
  }


  override def onIdleStream(key: StreamRef): Unit = {
    acknowledgedDelivery(key, CloseStreamFor(key), SpecificDestination(serviceRef), Some(_ => true))
    mappings = mappings filter {
      case (k, Some(v)) if v == key =>
        pendingMappings -= k
        false
      case _ => true
    }
  }


  override def onActiveStream(key: StreamRef): Unit = {
    acknowledgedDelivery(key, OpenStreamFor(key), SpecificDestination(serviceRef), Some(_ => true))
  }


  private def openLocalStream(subscriber: ActorRef, subj: Subject): Unit = {
    logger.info(s"!>>> openLocalStream($subscriber, $subj)")
    interests += subscriber -> (interests.getOrElse(subscriber, Set.empty) + subj)
    mappings get subj match {
      case Some(Some(key)) => initiateStreamFor(subscriber, key, subj)
      case Some(None) => publishNotAvailable(subscriber, subj)
      case None => requestMapping(subj)
    }
  }


  private def closeLocalStream(subscriber: ActorRef, subj: Subject): Unit = {
    logger.info(s"!>>> closeLocalStream($subscriber, $subj)")
    interests.get(subscriber) foreach { currentInterestsForSubscriber =>
      val remainingInterests = currentInterestsForSubscriber - subj
      closeStreamFor(subscriber, subj)
      if (remainingInterests.isEmpty) {
        context.unwatch(subscriber)
        interests -= subscriber
      } else {
        interests += subscriber -> remainingInterests
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

  private def requestMapping(subj: Subject): Unit = {
    logger.info(s"!>>> requestMapping($subj)")

    serviceRef ! GetMappingFor(subj)
    pendingMappings += subj -> now
  }

  private def publishNotAvailable(subscriber: ActorRef, subj: Subject): Unit = subscriber ! InvalidRequest(subj)

  private def publishNotAvailable(subj: Subject): Unit = interests foreach {
    case (ref, set) if set.contains(subj) => publishNotAvailable(ref, subj)
  }

  private def onReceivedStreamMapping(subj: Subject, maybeKey: Option[StreamRef]): Unit = {
    logger.info(s"!>>> onReceivedStreamMapping($subj, $maybeKey)")

    pendingMappings -= subj
    mappings get subj match {
      case Some(x) if x == maybeKey =>
      case Some(Some(x)) =>
        addStreamMapping(subj, maybeKey)
      case _ =>
        addStreamMapping(subj, maybeKey)
    }
  }

  private def addStreamMapping(subj: Subject, maybeKey: Option[StreamRef]) = {
    interests foreach {
      case (ref, v) => if (v.contains(subj)) closeStreamFor(ref, subj)
    }
    mappings += subj -> maybeKey
    maybeKey match {
      case Some(k) =>
        interests foreach {
          case (ref, v) => if (v.contains(subj)) {
            println(s"!>>> Initiated stream for $ref, $k, $subj")
            initiateStreamFor(ref, k, subj)
          }
        }
      case None => publishNotAvailable(subj)
    }
  }


  private def updateLocalStream(key: StreamRef, tran: StreamStateTransition): Unit = {
    logger.info(s"!>>>>>> Received stream update $key - $tran")
    upstreamDemandFulfilled(serviceRef, 1)
    if (!onStateTransition(key, tran)) serviceRef ! StreamResyncRequest(key)
  }


  onMessage {
    case m: Acknowledgeable => serviceRef forward m
    case OpenLocalStreamFor(subj) => openLocalStream(sender(), subj)
    case OpenLocalStreamsForAll(list) => list foreach { subj => openLocalStream(sender(), subj) }
    case CloseLocalStreamFor(subj) => closeLocalStream(sender(), subj)
    case CloseAllLocalStreams => closeAllFor(sender())
    case StreamMapping(subj, maybeKey) => onReceivedStreamMapping(subj, maybeKey)
    case StreamUpdate(key, tran) => updateLocalStream(key, tran)
  }

  override def shouldProcessAcknowledgeable(sender: ActorRef, m: Acknowledgeable): Boolean =
    if (sender == serviceRef) true else super.shouldProcessAcknowledgeable(sender, m)

  override def preStart(): Unit = {
    super.preStart()
    logger.info("!>>>> Started")
    startDemandProducerFor(serviceRef, withAcknowledgedDelivery = true)
    registerService(serviceKey)
  }

  private def closeAllFor(ref: ActorRef): Unit = {
    interests get ref foreach { set => set.foreach { subj => closeLocalStream(sender(), subj) } }
    terminateTarget(ref)
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    unregisterService(serviceKey)
    super.postStop()
    logger.info("!>>>> remote delegate stopped: " + self)
  }

  override def onConsumerDemand(sender: ActorRef, demand: Long): Unit = newConsumerDemand(sender, demand)


  override def onTerminated(ref: ActorRef): Unit = {
    closeAllFor(ref)
    super.onTerminated(ref)
  }


}

