/*
 * Copyright 2014-15 Intelix Pty Ltd
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
package rs.core.services

import akka.actor.{Actor, ActorRef, Address, Deploy}
import akka.remote.RemoteScope
import rs.core.actors._
import rs.core.config.ConfigOps.wrap
import rs.core.config.ServiceConfig
import rs.core.services.BaseServiceCell._
import rs.core.services.Messages.{SignalAckFailed, SignalAckOk}
import rs.core.services.internal.InternalMessages.SignalPayload
import rs.core.services.internal._
import rs.core.stream.{StreamPublishers, StreamState, StreamStateTransition}
import rs.core.utils.NanoTimer
import rs.core.{ServiceKey, Subject}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success}

object BaseServiceCell {

  case object StopRequest

  case class CloseStreamFor(streamKey: StreamId)

  case class OpenStreamFor(streamKey: StreamId)

  case class GetMappingFor(subj: Subject)

  case class StreamMapping(subj: Subject, mappedStreamKey: Option[StreamId])

  case class StreamResyncRequest(streamKey: StreamId)

  case class ServiceEndpoint(ref: ActorRef, address: Address)

  private case class OpenAgentAt(address: Address)

}

trait ServiceCellSysevents extends BaseActorSysevents with RemoteStreamsBroadcasterSysevents {
  val ServiceRunning = "ServiceRunning".info
  val NodeAvailable = "NodeAvailable".info
  val StartingRemoveAgent = "StartingRemoveAgent".info
  val RemoveAgentTerminated = "RemoveAgentTerminated".info
  val SignalProcessed = "SignalProcessed".info
  val SignalPayload = "SignalPayload".trace
  val RemoteEndpointRegistered = "RemoteEndpointRegistered".info
  val StreamInterestAdded = "StreamInterestAdded".info
  val StreamInterestRemoved = "StreamInterestRemoved".info
  val StreamResync = "StreamResync".info
  val IdleStream = "IdleStream".info
  val SubjectMapped = "SubjectMapped".info
  val SubjectMappingError = "SubjectMappingError".warn
}


trait WithId {
  def id: String
}

abstract class ServiceCell(id: String) extends ServiceWithId(id) with SingleStateActor with BaseServiceCell

abstract class FSMServiceCell[T](id: String) extends ServiceWithId(id) with FSMActor[T] with BaseServiceCell

abstract class ServiceWithId(override val id: String) extends Actor with WithId

trait BaseServiceCell
  extends BaseActor
    with WithId
    with ClusterAwareness
    with SimpleInMemoryAcknowledgedDelivery
    with StreamDemandBinding
    with RemoteStreamsBroadcaster
    with MessageAcknowledging
    with StreamPublishers
    with ServiceCellSysevents {

  implicit lazy val serviceCfg = ServiceConfig(config.asConfig(id))
  lazy val serviceKey: ServiceKey = id

  type SubjectToStreamKeyMapper = PartialFunction[Subject, Option[StreamId]]
  type StreamKeyToUnit = PartialFunction[StreamId, Unit]
  type SignalHandler = PartialFunction[(Subject, Any), Option[SignalResponse]]
  type SignalHandlerAsync = PartialFunction[(Subject, Any), Option[Future[SignalResponse]]]

  val nodeRoles: Set[String] = Set.empty
  private var subjectToStreamKeyMapperFunc: SubjectToStreamKeyMapper = {
    case _ => None
  }
  private var streamActiveFunc: StreamKeyToUnit = {
    case _ =>
  }
  private var streamPassiveFunc: StreamKeyToUnit = {
    case _ =>
  }
  private var signalHandlerFunc: PartialFunction[(Subject, Any), Option[Any]] = {
    case _ => None
  }
  private var activeStreams: Set[StreamId] = Set.empty
  private var activeAgents: Map[Address, AgentView] = Map.empty

  final def onSubjectMapping(f: SubjectToStreamKeyMapper): Unit = subjectToStreamKeyMapperFunc = f orElse subjectToStreamKeyMapperFunc

  final def onStreamActive(f: StreamKeyToUnit): Unit = streamActiveFunc = f orElse streamActiveFunc

  final def onStreamPassive(f: StreamKeyToUnit): Unit = streamPassiveFunc = f orElse streamPassiveFunc

  final def onSignal(f: SignalHandler): Unit = signalHandlerFunc = f orElse signalHandlerFunc
  final def onSignalAsync(f: SignalHandlerAsync): Unit = signalHandlerFunc = f orElse signalHandlerFunc

  final def isStreamActive(streamKey: StreamId) = activeStreams.contains(streamKey)

  final def performStateTransition(key: StreamId, transition: => StreamStateTransition) = stateTransitionFor(key, transition)

  final def currentStreamState(key: StreamId): Option[StreamState] = stateOf(key)

  final override def onConsumerDemand(consumer: ActorRef, demand: Long): Unit = newConsumerDemand(consumer, demand)

  implicit def signalResponseToOptionWrapper(x: SignalResponse): Option[SignalResponse] = Some(x)
  implicit def futureOfSignalResponseToOptionWrapper(x: Future[SignalResponse]): Option[Future[SignalResponse]] = Some(x)
  implicit def streamIdToOptionWrapper(x: StreamId): Option[StreamId] = Some(x)

  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('service -> serviceKey)

  implicit val execCtx = context.dispatcher

  onClusterMemberUp {
    case (address, roles) if nodeRoles.isEmpty || roles.exists(nodeRoles.contains) =>
      NodeAvailable('address -> address, 'host -> address.host, 'roles -> roles)
      ensureAgentIsRunningAt(address)
      reinitialiseStreams(address)
  }

  onMessage {
    case m: SignalPayload =>
      val timer = NanoTimer()
      SignalPayload('correlation -> m.correlationId, 'payload -> m.payload)
      if (m.expireAt > now) {
        val origin = sender()
        signalHandlerFunc(m.subj, m.payload) match {
          case Some(SignalOk(p)) =>
            origin ! SignalAckOk(m.correlationId, m.subj, p)
            SignalProcessed('correlation -> m.correlationId, 'subj -> m.subj, 'result -> "success", 'ms -> timer.toMillis)
          case Some(SignalFailed(p)) =>
            origin ! SignalAckFailed(m.correlationId, m.subj, p)
            SignalProcessed('correlation -> m.correlationId, 'subj -> m.subj, 'result -> "failure", 'ms -> timer.toMillis)
          case Some(f: Future[_]) =>
            f.onComplete {
              case Success(SignalOk(p)) =>
                origin ! SignalAckOk(m.correlationId, m.subj, p)
                SignalProcessed('correlation -> m.correlationId, 'subj -> m.subj, 'result -> "success", 'ms -> timer.toMillis)
              case Success(SignalFailed(p)) =>
                origin ! SignalAckFailed(m.correlationId, m.subj, p)
                SignalProcessed('correlation -> m.correlationId, 'subj -> m.subj, 'result -> "failure", 'ms -> timer.toMillis)
              case Success(_) =>
                SignalProcessed('correlation -> m.correlationId, 'subj -> m.subj, 'result -> "ignored", 'ms -> timer.toMillis)
              case Failure(t) =>
                origin ! SignalAckFailed(m.correlationId, m.subj, None)
                SignalProcessed('correlation -> m.correlationId, 'subj -> m.subj, 'result -> "failure", 'reason -> t, 'ms -> timer.toMillis)
            }
          case None =>
            SignalProcessed('correlation -> m.correlationId, 'subj -> m.subj, 'result -> "ignored", 'ms -> timer.toMillis)
          case _ =>
        }
      } else SignalProcessed('correlation -> m.correlationId, 'subj -> m.subj, 'expired -> "true", 'ms -> timer.toMillis)

    case ServiceEndpoint(ref, address) => addEndpointAddress(address, ref)
    case OpenAgentAt(address) => if (isAddressReachable(address)) ensureAgentIsRunningAt(address)
    case GetMappingFor(subj) =>
      subjectToStreamKeyMapperFunc(subj) match {
        case None =>
          sender() ! StreamMapping(subj, None)
          SubjectMappingError('subj -> subj)
        case x@Some(streamKey) =>
          sender() ! StreamMapping(subj, x)
          SubjectMapped('stream -> streamKey, 'subj -> subj)
      }
    case OpenStreamFor(streamKey) =>
      registerStreamInterest(streamKey, sender())
    case CloseStreamFor(streamKey) => closeStreamAt(sender(), streamKey)
    case StreamResyncRequest(key) => StreamResync { ctx =>
      ctx +('stream -> key, 'ref -> sender())
      reopenStream(sender(), key)
    }
    case StopRequest => context.parent ! StopRequest

  }


  private def reopenStream(endpointRef: ActorRef, key: StreamId) = {
    closeStreamFor(endpointRef, key)
    initiateStreamFor(endpointRef, key)
  }

  onActorTerminated { ref =>
    activeAgents get ref.path.address foreach { loc =>
      if (loc.agent == ref) {
        RemoveAgentTerminated('location -> ref.path.address, 'ref -> ref)
        cancelMessages(SpecificDestination(ref))
        activeAgents -= ref.path.address
        loc.endpoint foreach { epRef =>
          loc.currentStreams foreach { streamKey => closeStreamAt(epRef, streamKey) }
        }
        scheduleOnce(1 seconds, OpenAgentAt(ref.path.address))
      }
    }
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    ServiceRunning()
  }

  override def componentId: String = "Service." + id

  private def ensureAgentIsRunningAt(address: Address) =
    if (!activeAgents.contains(address))
      StartingRemoveAgent { ctx =>
        ctx +('address -> address, 'host -> address.host)

        val id = shortUUID

        val newAgent = context.watch(
          context.actorOf(NodeLocalServiceStreamEndpoint.remoteStreamAgentProps(serviceKey, self, id).withDeploy(Deploy(scope = RemoteScope(address))), s"agt-$serviceKey-$id")
        )

        ctx + ('remotePath -> newAgent.path)

        val newActiveLocation = new AgentView(newAgent)
        activeAgents += address -> newActiveLocation
      }

  private def reinitialiseStreams(address: Address) =
    for (
      agent <- activeAgents.get(address);
      endpoint <- agent.endpoint
    ) agent.currentStreams.foreach { streamId => reopenStream(endpoint, streamId) }


  private def closeStreamAt(endpoint: ActorRef, streamKey: StreamId) = {
    agentWithEndpointAt(endpoint) foreach { loc =>
      StreamInterestRemoved { ctx =>
        loc.endpoint foreach { ref => closeStreamFor(ref, streamKey) }
        loc remove streamKey
        val total = loc.currentStreams.size
        ctx +('stream -> streamKey, 'location -> endpoint.path.address, 'streamsAtLocation -> total)
      }
    }
    if (!hasAgentWithInterestIn(streamKey)) IdleStream { ctx =>
      ctx + ('stream -> streamKey)
      activeStreams -= streamKey
      streamPassiveFunc(streamKey)
    }
  }

  private def hasAgentWithInterestIn(key: StreamId): Boolean = activeAgents.values exists (_.hasInterestIn(key))

  private def agentWithEndpointAt(ref: ActorRef) = activeAgents.values.find(_.endpoint.contains(ref))

  private def registerStreamInterest(streamKey: StreamId, requestor: ActorRef): Unit =
    agentWithEndpointAt(requestor) foreach { agentView =>
      StreamInterestAdded { ctx =>
        val existingStream = isStreamActive(streamKey)
        activeStreams += streamKey
        agentView add streamKey
        agentView.endpoint foreach { ref => initiateStreamFor(ref, streamKey) }
        if (!existingStream) streamActiveFunc(streamKey)

        val total = agentView.currentStreams.size
        ctx +('stream -> streamKey, 'location -> requestor.path.address, 'existing -> existingStream, 'streamsAtLocation -> total)
      }
    }

  private def addEndpointAddress(address: Address, endpoint: ActorRef): Unit = RemoteEndpointRegistered { ctx =>
    ctx +('location -> address, 'ref -> endpoint)
    activeAgents get address foreach { agentView =>
      agentView.addEndpoint(endpoint)
      initiateTarget(endpoint)
    }
  }

  protected def terminate(reason: String) = throw new RuntimeException(reason)

  sealed trait SignalResponse

  case class SignalOk(payload: Option[Any] = None) extends SignalResponse
  object SignalOk {
    def apply(): SignalOk = SignalOk(None)
    def apply(any: Any): SignalOk = any match {
      case x: Option[_] => SignalOk(x)
      case x => SignalOk(Some(x))
    }
  }

  case class SignalFailed(payload: Option[Any] = None) extends SignalResponse
  object SignalFailed {
    def apply(): SignalFailed = SignalFailed(None)
    def apply(any: Any): SignalFailed = any match {
      case x: Option[_] => SignalFailed(x)
      case x => SignalFailed(Some(x))
    }
  }

  private class AgentView(val agent: ActorRef) {

    var endpoint: Option[ActorRef] = None
    private var streams: Set[StreamId] = Set.empty

    def addEndpoint(ref: ActorRef): Unit = endpoint = Some(ref)

    def add(s: StreamId) = streams += s

    def remove(s: StreamId) = streams -= s

    def currentStreams = streams

    def hasInterestIn(s: StreamId) = streams contains s

  }

}
