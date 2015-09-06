package rs.core.services

import akka.actor.{ActorRef, Address, Deploy}
import akka.remote.RemoteScope
import rs.core.actors.{ActorWithComposableBehavior, ClusterAwareness}
import rs.core.services.Messages.{SignalAckFailed, SignalAckOk}
import rs.core.services.ServiceCell._
import rs.core.services.internal.InternalMessages.SignalPayload
import rs.core.services.internal._
import rs.core.services.internal.acks.SimpleInMemoryAcknowledgedDelivery
import rs.core.stream.{StreamState, StreamStateTransition}
import rs.core.tools.metrics.WithCHMetrics
import rs.core.{ServiceKey, Subject}

import scala.concurrent.duration._
import scala.language.postfixOps

object ServiceCell {

  // TODO mark case classes as private where needed
  case class StreamKeyPerLocationId(streamKey: StreamId, ref: ActorRef)

  case class OpenAgentAt(address: Address)

  case class CloseStreamFor(streamKey: StreamId)

  case class OpenStreamFor(streamKey: StreamId)

  case class GetMappingFor(subj: Subject)

  case class StreamMapping(subj: Subject, mappedStreamKey: Option[StreamId])

  case class StreamResyncRequest(streamKey: StreamId)

  case class ServiceEndpoint(ref: ActorRef, address: Address)

}

abstract class ServiceCell(id: String)
  extends ActorWithComposableBehavior
  with WithCHMetrics
  with ClusterAwareness
  with SimpleInMemoryAcknowledgedDelivery
  with StreamDemandBinding
  with MultipleStreamsBroadcaster
  with MessageAcknowledging {


  class AgentView(val agent: ActorRef) {

    var endpoint: Option[ActorRef] = None

    def addEndpoint(ref: ActorRef): Unit = endpoint = Some(ref)


    private var streams: Set[StreamId] = Set.empty

    def add(s: StreamId) = streams += s

    def remove(s: StreamId) = streams -= s

    def currentStreams = streams

    def hasInterestIn(s: StreamId) = streams contains s

  }


  type SubjectToStreamKeyMapper = PartialFunction[Subject, Option[StreamId]]
  type StreamKeyToUnit = PartialFunction[StreamId, Unit]
  type SignalHandler = PartialFunction[(Subject, Any), Option[SignalResponse]]
  private var subjectToStreamKeyMapperFunc: SubjectToStreamKeyMapper = {
    case _ => None
  }
  private var streamActiveFunc: StreamKeyToUnit = {
    case _ =>
  }
  private var streamPassiveFunc: StreamKeyToUnit = {
    case _ =>
  }
  private var signalHandlerFunc: SignalHandler = {
    case _ => None
  }

  private var activeStreams: Set[StreamId] = Set.empty
  private var activeAgents: Map[Address, AgentView] = Map.empty

  val serviceKey: ServiceKey = id

  final def onSubject(f: SubjectToStreamKeyMapper): Unit = subjectToStreamKeyMapperFunc = f orElse subjectToStreamKeyMapperFunc

  final def onStreamActive(f: StreamKeyToUnit): Unit = streamActiveFunc = f orElse streamActiveFunc

  final def onStreamPassive(f: StreamKeyToUnit): Unit = streamPassiveFunc = f orElse streamPassiveFunc

  final def isActiveStream(streamKey: StreamId) = activeStreams.contains(streamKey)

  final def onStateTransition(key: StreamId, transition: => StreamStateTransition) = stateTransitionFor(key, transition)

  final def currentStreamState(key: StreamId): Option[StreamState] = stateOf(key)

  final def onSignal(f: SignalHandler): Unit = signalHandlerFunc = f orElse signalHandlerFunc

  override def onConsumerDemand(consumer: ActorRef, demand: Long): Unit = newConsumerDemand(consumer, demand)


  sealed trait SignalResponse

  case class SignalOk(payload: Option[Any] = None) extends SignalResponse

  case class SignalFailed(payload: Option[Any] = None) extends SignalResponse


  private def ensureAgentIsRunningAt(address: Address) =
    if (!activeAgents.contains(address)) {

      println(s"!>>> Will be starting on : $address")

      val newAgent = context.watch(context.actorOf(ServiceStreamingEndpointActor.props(serviceKey, self).withDeploy(Deploy(scope = RemoteScope(address)))))

      val newActiveLocation = new AgentView(newAgent)
      activeAgents += address -> newActiveLocation
      // TODO notify registry
    }

  override def onClusterMemberUp(address: Address): Unit = {
    println(s"!>>> Up: $address")
    ensureAgentIsRunningAt(address)
    super.onClusterMemberUp(address)
  }

  onActorTerminated { ref =>
    activeAgents get ref.path.address foreach { loc =>
      cancelMessages(SpecificDestination(ref))
      activeAgents -= ref.path.address
      loc.endpoint foreach { epRef =>
        loc.currentStreams foreach { streamKey => closeStreamAt(epRef, streamKey) }
      }
      scheduleOnce(1 seconds, OpenAgentAt(ref.path.address))
    }
  }


  private def closeStreamAt(endpoint: ActorRef, streamKey: StreamId) = {
    agentWithEndpointAt(endpoint) foreach { loc =>
      loc.endpoint foreach { ref => closeStreamFor(ref, streamKey) }
      loc remove streamKey
    }
    if (!hasAgentWithInterestIn(streamKey)) {
      activeStreams -= streamKey
      streamPassiveFunc(streamKey)
    }
  }

  private def hasAgentWithInterestIn(key: StreamId): Boolean = activeAgents.values exists (_.hasInterestIn(key))

  private def agentWithEndpointAt(ref: ActorRef) = activeAgents.values.find(_.endpoint.contains(ref))

  private def registerStreamInterest(streamKey: StreamId, requestor: ActorRef): Unit = {

    agentWithEndpointAt(requestor) foreach { agentView =>
      val existingStream = isActiveStream(streamKey)

      activeStreams += streamKey

      agentView add streamKey

      logger.info(s"!>>>> registerStreamInterest, $streamKey, subscriber = $requestor")
      agentView.endpoint foreach { ref => initiateStreamFor(ref, streamKey) }
      if (!existingStream) streamActiveFunc(streamKey)
    }

  }


  private def publishStreamMapping(agent: ActorRef, subject: Subject, mapping: Option[StreamId]) =
    acknowledgedDelivery(
      (agent, subject),
      StreamMapping(subject, mapping),
      SpecificDestination(agent),
      Some(_ => true)
    )


  private def addEndpointAddress(address: Address, endpoint: ActorRef): Unit = {

    logger.info("!>>>> Added endpoint: " + endpoint + "@" + address)
    activeAgents get address foreach { agentView =>
      agentView.addEndpoint(endpoint)
      initiateTarget(endpoint)
    }
  }

  onMessage {
    case m: SignalPayload =>
      logger.info("!>>>> Received SignalPayload : " + m)
      if (m.expireAt > now) {
        signalHandlerFunc(m.subj, m.payload) match {
          case Some(SignalOk(p)) => sender() ! SignalAckOk(m.correlationId, m.subj, p)
          case Some(SignalFailed(p)) => sender() ! SignalAckFailed(m.correlationId, m.subj, p)
          case None => // TODO log
        }
      } else logger.warn("!>>>> Message expired! " + m)
    case ServiceEndpoint(ref, address) => addEndpointAddress(address, ref)
    case OpenAgentAt(address) => if (isAddressReachable(address)) ensureAgentIsRunningAt(address)
    case GetMappingFor(subj) =>
      subjectToStreamKeyMapperFunc(subj) match {
        case None =>

          println(s"!>>> in $serviceKey ${getClass.getSimpleName} Requested mapping for $subj, found none")
          sender() ! StreamMapping(subj, None)
        case x@Some(streamKey) =>
          println(s"!>>> Requested mapping for $subj, found " + streamKey)
          sender() ! StreamMapping(subj, x)
      }
    case CloseStreamFor(streamKey) => closeStreamAt(sender(), streamKey)
    case OpenStreamFor(streamKey) =>
      logger.info(s"!>>>> OpenStreamFor($streamKey)")
      registerStreamInterest(streamKey, sender())
    case StreamResyncRequest(key) =>
      logger.info(s"!>>>> StreamResyncRequest, $key requested by ${sender()}")
      closeStreamFor(sender(), key)
      initiateStreamFor(sender(), key)
  }

  override def componentId: String = id
}
