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
package au.com.intelix.rs.core.services

import akka.actor.{Actor, ActorRef, Address, Deploy}
import akka.remote.RemoteScope
import au.com.intelix.essentials.time.NanoTimer
import au.com.intelix.rs.core.actors._
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.evt.{InfoE, WarningE}
import au.com.intelix.rs.core.config.ServiceConfig
import au.com.intelix.rs.core.services.Messages.{SignalAckFailed, SignalAckOk}
import au.com.intelix.rs.core.services.internal.InternalMessages.SignalPayload
import au.com.intelix.rs.core.services.internal._
import au.com.intelix.rs.core.stream.{StreamPublishers, StreamState, StreamStateTransition}
import au.com.intelix.rs.core.{Ser, ServiceKey, Subject}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success}

object BaseServiceActor {

  case class CloseStreamFor(streamKey: StreamId) extends Ser

  case class OpenStreamFor(streamKey: StreamId) extends Ser

  case class GetMappingFor(subj: Subject) extends Ser

  case class StreamMapping(subj: Subject, mappedStreamKey: Option[StreamId]) extends Ser

  case class StreamResyncRequest(streamKey: StreamId) extends Ser

  case class ServiceEndpoint(ref: ActorRef, id: String) extends Ser

  private case class OpenAgentAt(address: Address)

  case object StopRequest

}

object ServiceEvt {

  case object EvtServiceRunning extends InfoE

  case object EvtNodeAvailable extends InfoE

  case object EvtStartingRemoteAgent extends InfoE

  case object EvtRemoveAgentTerminated extends InfoE

  case object EvtSignalProcessed extends InfoE

  case object EvtSignalPayload extends InfoE
  case object EvtSignalResponsePayload extends InfoE

  case object EvtRemoteEndpointRegistered extends InfoE

  case object EvtStreamInterestAdded extends InfoE

  case object EvtStreamInterestRemoved extends InfoE

  case object EvtStreamResync extends InfoE

  case object EvtIdleStream extends InfoE

  case object EvtSubjectMapped extends InfoE

  case object EvtSubjectMappingError extends WarningE

}

abstract class StatelessServiceActor extends StatelessActor with BaseServiceActor

trait BaseServiceActor
  extends BaseActor
  with ClusterAwareness
  with SimpleInMemoryAcknowledgedDelivery
  with StreamDemandBinding
  with RemoteStreamsBroadcaster
  with MessageAcknowledging
  with StreamPublishers {

  import BaseServiceActor._

  type SubjectToStreamIdMapper = PartialFunction[Subject, Option[StreamId]]
  type SubjectToFutureStreamIdMapper = PartialFunction[Subject, Future[Option[StreamId]]]
  type StreamEventCallback = PartialFunction[StreamId, Unit]
  type SignalHandler = PartialFunction[(Subject, Any), Option[SignalResponse]]
  type SignalHandlerAsync = PartialFunction[(Subject, Any), Option[Future[SignalResponse]]]

  private val serviceId = self.path.name

  implicit lazy val serviceCfg = ServiceConfig(config.asConfig(serviceId))

  implicit lazy val serviceKey: ServiceKey = serviceId

  implicit val execCtx = context.dispatcher

  val nodeRoles: Set[String] = Set.empty

  private var subjectToStreamKeyMapperFunc: SubjectToStreamIdMapper = {
    case _ => None
  }
  private var subjectToFutureStreamKeyMapperFunc: SubjectToFutureStreamIdMapper = PartialFunction.empty

  private var streamActiveFunc: StreamEventCallback = {
    case _ =>
  }
  private var streamPassiveFunc: StreamEventCallback = {
    case _ =>
  }
  private var signalHandlerFunc: PartialFunction[(Subject, Any), Option[Any]] = {
    case _ => None
  }
  private var defaultSignalResponseFunc: PartialFunction[(Subject, Any), Option[SignalResponse]] = {
    case _ => None
  }

  private var activeStreams: Set[StreamId] = Set.empty
  private var activeAgents: Map[String, AgentView] = Map.empty

  final override def onConsumerDemand(consumer: ActorRef, demand: Long): Unit = newConsumerDemand(consumer, demand)

  final def onSubjectMapping(f: SubjectToStreamIdMapper): Unit = subjectToStreamKeyMapperFunc = f orElse subjectToStreamKeyMapperFunc
  final def onSubjectMappingAsync(f: SubjectToFutureStreamIdMapper): Unit = subjectToFutureStreamKeyMapperFunc = f orElse subjectToFutureStreamKeyMapperFunc

  final def onStreamActive(f: StreamEventCallback): Unit = streamActiveFunc = f orElse streamActiveFunc

  final def onStreamPassive(f: StreamEventCallback): Unit = streamPassiveFunc = f orElse streamPassiveFunc

  final def onSignal(f: SignalHandler): Unit = signalHandlerFunc = f orElse signalHandlerFunc

  final def onSignalAsync(f: SignalHandlerAsync): Unit = signalHandlerFunc = f orElse signalHandlerFunc

  final def defaultSignalResponseFor(f: PartialFunction[(Subject, Any), Option[SignalResponse]]): Unit = defaultSignalResponseFunc = f orElse defaultSignalResponseFunc


  final def performStateTransition(key: StreamId, transition: => StreamStateTransition) = stateTransitionFor(key, transition)

  final def currentStreamState(key: StreamId): Option[StreamState] = stateOf(key)

  implicit def signalResponseToOptionWrapper(x: SignalResponse): Option[SignalResponse] = Some(x)

  implicit def futureOfSignalResponseToOptionWrapper(x: Future[SignalResponse]): Option[Future[SignalResponse]] = Some(x)

  implicit def streamIdToOptionWrapper(x: StreamId): Option[StreamId] = Some(x)

  implicit def stringToOptionStreamIdWrapper(x: String): Option[StreamId] = Some(x)

  implicit def tupleToOptionStreamIdWrapper[T](x: (String, T)): Option[StreamId] = Some(x)

  addEvtFields('service -> serviceKey)

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    raise(ServiceEvt.EvtServiceRunning)
  }

  onClusterMemberUp {
    case (address, roles) if nodeRoles.isEmpty || roles.exists(nodeRoles.contains) =>
      raise(ServiceEvt.EvtNodeAvailable, 'address -> address, 'host -> address.host, 'roles -> roles)
      ensureAgentIsRunningAt(address)
      reinitialiseStreams(address)
  }

  onMessage {
    case m: SignalPayload =>
      val dsr = defaultSignalResponseFunc
      val timer = NanoTimer()
      raise(ServiceEvt.EvtSignalPayload, 'correlation -> m.correlationId, 'payload -> m.payload)
      if (m.expireAt > now) {
        val origin = sender()
        signalHandlerFunc((m.subj, m.payload)) match {
          case Some(SignalOk(p)) =>
            origin ! SignalAckOk(m.correlationId, m.subj, p)
            raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "success", 'ms -> timer.toMillis)
            raise(ServiceEvt.EvtSignalResponsePayload, 'correlation -> m.correlationId, 'payload -> p)
          case Some(SignalFailed(p)) =>
            origin ! SignalAckFailed(m.correlationId, m.subj, p)
            raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "failure", 'ms -> timer.toMillis)
            raise(ServiceEvt.EvtSignalResponsePayload, 'correlation -> m.correlationId, 'payload -> p)
          case Some(f: Future[_]) =>
            f.onComplete {
              case Success(SignalOk(p)) =>
                origin ! SignalAckOk(m.correlationId, m.subj, p)
                raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "success", 'ms -> timer.toMillis)
                raise(ServiceEvt.EvtSignalResponsePayload, 'correlation -> m.correlationId, 'payload -> p)
              case Success(SignalFailed(p)) =>
                origin ! SignalAckFailed(m.correlationId, m.subj, p)
                raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "failure", 'ms -> timer.toMillis)
                raise(ServiceEvt.EvtSignalResponsePayload, 'correlation -> m.correlationId, 'payload -> p)
              case Success(_) =>
                dsr((m.subj, m.payload)) match {
                  case Some(SignalOk(p)) =>
                    origin ! SignalAckOk(m.correlationId, m.subj, p)
                    raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "success", 'default -> true, 'ms -> timer.toMillis)
                    raise(ServiceEvt.EvtSignalResponsePayload, 'correlation -> m.correlationId, 'payload -> p)
                  case Some(SignalFailed(p)) =>
                    origin ! SignalAckFailed(m.correlationId, m.subj, p)
                    raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "failure", 'default -> true, 'ms -> timer.toMillis)
                    raise(ServiceEvt.EvtSignalResponsePayload, 'correlation -> m.correlationId, 'payload -> p)
                  case _ =>
                    raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "ignored", 'ms -> timer.toMillis)
                }
              case Failure(t) =>
                origin ! SignalAckFailed(m.correlationId, m.subj, None)
                raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "failure", 'reason -> t, 'ms -> timer.toMillis)
            }
          case None =>
            dsr((m.subj, m.payload)) match {
              case Some(SignalOk(p)) =>
                origin ! SignalAckOk(m.correlationId, m.subj, p)
                raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "success", 'default -> true, 'ms -> timer.toMillis)
                raise(ServiceEvt.EvtSignalResponsePayload, 'correlation -> m.correlationId, 'payload -> p)
              case Some(SignalFailed(p)) =>
                origin ! SignalAckFailed(m.correlationId, m.subj, p)
                raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "failure", 'default -> true, 'ms -> timer.toMillis)
                raise(ServiceEvt.EvtSignalResponsePayload, 'correlation -> m.correlationId, 'payload -> p)
              case _ =>
                raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'result -> "ignored", 'ms -> timer.toMillis)
            }
          case _ =>
        }
      } else raise(ServiceEvt.EvtSignalProcessed, 'correlation -> m.correlationId, 'subj -> m.subj, 'expired -> "true", 'ms -> timer.toMillis)

    case ServiceEndpoint(ref, id) => addEndpointAddress(id, ref)
    case OpenAgentAt(address) => if (isAddressReachable(address)) ensureAgentIsRunningAt(address)
    case GetMappingFor(subj) if subjectToFutureStreamKeyMapperFunc.isDefinedAt(subj) =>
      val ref = sender()
      subjectToFutureStreamKeyMapperFunc(subj).onComplete {
        case Failure(x) =>
          //          sender() ! StreamMapping(subj, None)
          raise(ServiceEvt.EvtSubjectMappingError, 'subj -> subj, 'cause -> x)
        case Success(None) =>
          ref ! StreamMapping(subj, None)
          raise(ServiceEvt.EvtSubjectMappingError, 'subj -> subj)
        case Success(x@Some(streamKey)) =>
          ref ! StreamMapping(subj, x)
          raise(ServiceEvt.EvtSubjectMapped, 'stream -> streamKey, 'subj -> subj)
      }
    case GetMappingFor(subj) if subjectToStreamKeyMapperFunc.isDefinedAt(subj) =>
      subjectToStreamKeyMapperFunc(subj) match {
        case None =>
          sender() ! StreamMapping(subj, None)
          raise(ServiceEvt.EvtSubjectMappingError, 'subj -> subj)
        case x@Some(streamKey) =>
          sender() ! StreamMapping(subj, x)
          raise(ServiceEvt.EvtSubjectMapped, 'stream -> streamKey, 'subj -> subj)
      }
    case OpenStreamFor(streamKey) =>
      registerStreamInterest(streamKey, sender())
    case CloseStreamFor(streamKey) => closeStreamAt(sender(), streamKey)
    case StreamResyncRequest(key) =>
      raise(ServiceEvt.EvtStreamResync, 'stream -> key, 'ref -> sender())
      reopenStream(sender(), key)
    case StopRequest => context.parent ! StopRequest
  }

  onActorTerminated { ref =>
    activeAgents get ref.path.address.toString foreach { loc =>
      if (loc.agent == ref) {
        raise(ServiceEvt.EvtRemoveAgentTerminated, 'location -> ref.path.address, 'ref -> ref)
        cancelMessages(SpecificDestination(ref))
        activeAgents -= ref.path.address.toString
        loc.endpoint foreach { epRef =>
          loc.currentStreams foreach { streamKey => closeStreamAt(epRef, streamKey) }
        }
        scheduleOnce(100 millis, OpenAgentAt(ref.path.address))
      }
    }
  }

  protected def terminate(reason: String) = throw new RuntimeException(reason)

  private def ensureAgentIsRunningAt(address: Address) = {
    if (!activeAgents.contains(address.toString)) {

      val id = randomUUID

      val name = s"agt-$serviceKey-$id"

      val newAgent = context.watch(
        context.actorOf(NodeLocalServiceStreamEndpoint
          .remoteStreamAgentProps(serviceKey, self, id)
          .withDeploy(Deploy(scope = RemoteScope(address))), name)
      )

      raise(ServiceEvt.EvtStartingRemoteAgent, 'address -> address, 'host -> address.host, 'name -> name, 'remotePath -> newAgent.path)
      val newActiveLocation = new AgentView(newAgent)
      activeAgents += address.toString -> newActiveLocation
    }
  }

  private def reinitialiseStreams(address: Address) =
    for (
      agent <- activeAgents.get(address.toString);
      endpoint <- agent.endpoint
    ) agent.currentStreams.foreach { streamId => reopenStream(endpoint, streamId) }

  private def reopenStream(endpointRef: ActorRef, key: StreamId) = {
    closeStreamFor(endpointRef, key)
    initiateStreamFor(endpointRef, key)
  }

  private def closeStreamAt(endpoint: ActorRef, streamKey: StreamId) = {
    agentWithEndpointAt(endpoint) foreach { loc =>
      loc.endpoint foreach { ref => closeStreamFor(ref, streamKey) }
      loc remove streamKey
      val total = loc.currentStreams.size
      raise(ServiceEvt.EvtStreamInterestRemoved, 'stream -> streamKey, 'location -> endpoint.path.address, 'streamsAtLocation -> total)
    }
    if (!hasAgentWithInterestIn(streamKey)) {
      raise(ServiceEvt.EvtIdleStream, 'stream -> streamKey)
      activeStreams -= streamKey
      streamPassiveFunc(streamKey)
    }
  }

  private def hasAgentWithInterestIn(key: StreamId): Boolean = activeAgents.values exists (_.hasInterestIn(key))

  private def agentWithEndpointAt(ref: ActorRef) = activeAgents.values.find(_.endpoint.contains(ref))

  private def registerStreamInterest(streamKey: StreamId, requestor: ActorRef): Unit =
    agentWithEndpointAt(requestor) foreach { agentView =>
      val existingStream = isStreamActive(streamKey)
      activeStreams += streamKey
      agentView add streamKey
      agentView.endpoint foreach { ref => initiateStreamFor(ref, streamKey) }
      if (!existingStream) streamActiveFunc(streamKey)

      val total = agentView.currentStreams.size
      raise(ServiceEvt.EvtStreamInterestAdded, 'stream -> streamKey, 'location -> requestor.path.address, 'existing -> existingStream, 'streamsAtLocation -> total)
    }

  final def isStreamActive(streamKey: StreamId) = activeStreams.contains(streamKey)

  private def addEndpointAddress(id: String, endpoint: ActorRef): Unit = {
    activeAgents get id foreach { agentView =>
      agentView.addEndpoint(endpoint)
      initiateTarget(endpoint)
    }
    raise(ServiceEvt.EvtRemoteEndpointRegistered, 'location -> id, 'ref -> endpoint)
  }

  sealed trait SignalResponse

  case class SignalOk(payload: Option[Any] = None) extends SignalResponse

  case class SignalFailed(payload: Option[Any] = None) extends SignalResponse

  object SignalOk {
    def apply(): SignalOk = SignalOk(None)

    def apply(any: Any): SignalOk = any match {
      case x: Option[_] => SignalOk(x)
      case x => SignalOk(Some(x))
    }
  }

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
