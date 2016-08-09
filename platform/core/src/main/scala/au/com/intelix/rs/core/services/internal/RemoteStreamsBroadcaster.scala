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

import akka.actor.ActorRef
import com.typesafe.config._
import au.com.intelix.rs.core.actors.BaseActor
import au.com.intelix.evt.{EvtSource, EvtContext, TraceE}
import au.com.intelix.rs.core.services.StreamId
import au.com.intelix.rs.core.services.internal.InternalMessages.StreamUpdate
import au.com.intelix.rs.core.stream.{StreamState, StreamStateTransition}

import scala.collection.mutable


object RemoteStreamsBroadcaster {

  object Evt {
    case object StreamStateTransition extends TraceE
    case object InitiatingStreamForDestination extends TraceE
    case object ClosingStreamForDestination extends TraceE
    case object StreamUpdateSent extends TraceE
  }

}

trait RemoteStreamsBroadcaster extends BaseActor {

  import RemoteStreamsBroadcaster._

  private val targets: mutable.Map[ActorRef, ConsumerWithStreamSinks] = mutable.HashMap()
  private val streams: mutable.Map[StreamId, StreamBroadcaster] = mutable.HashMap()

  final def stateOf(key: StreamId): Option[StreamState] = streams get key flatMap (_.state)

  final def newConsumerDemand(consumer: ActorRef, demand: Long): Unit = {
    locateTarget(consumer).addDemand(demand)
  }

  final def stateTransitionFor(key: StreamId, transition: => StreamStateTransition): Boolean = {
    streams get key match {
      case None =>
        raise(Evt.StreamStateTransition, 'stream -> key, 'active -> false)
        true
      case Some(s) =>
        val result = s.run(transition)
        raise(Evt.StreamStateTransition, 'stream -> key, 'active -> true, 'successful -> result, 'tx -> transition)
        result
    }
  }

  final def initiateTarget(ref: ActorRef): Unit = if (!targets.contains(ref)) newTarget(ref)

  private def locateTarget(ref: ActorRef) = targets.getOrElse(ref, newTarget(ref))

  final def initiateStreamFor(ref: ActorRef, key: StreamId) = {
    val target = locateTarget(ref)
    val stream = streams getOrElse(key, {
      newStreamBroadcaster(key)
    })

    val sink = target locateExistingSinkFor key match {
      case None =>
        val newSink = target addStream key
        stream addSink newSink
        newSink
      case Some(s) => s
    }
    sink.resetDownstreamView()
    raise(Evt.InitiatingStreamForDestination, 'stream -> key, 'ref -> ref)
  }


  private def newStreamBroadcaster(key: StreamId) = {
    val sb = new StreamBroadcaster()
    streams += key -> sb
    sb
  }

  private def newTarget(ref: ActorRef) = {
    val target = new ConsumerWithStreamSinks(context.watch(ref), self, evtSource)
    targets += ref -> target
    target
  }

  final def closeStreamFor(ref: ActorRef, key: StreamId) = {
    targets get ref foreach { target =>
      target locateExistingSinkFor key foreach { existingSink =>
        target.closeStream(key)
        streams get key foreach { stream =>
          stream removeSink existingSink
        }
      }
    }
    raise(Evt.ClosingStreamForDestination, 'stream -> key, 'ref -> ref)
  }

  onActorTerminated { ref => targets -= ref }

  private class ConsumerWithStreamSinks(val ref: ActorRef, self: ActorRef, parentEvtSource: EvtSource)(implicit val config: Config) extends ConsumerDemandTracker with EvtContext {
    private val streamKeyToSink: mutable.Map[StreamId, StreamSink] = mutable.HashMap()
    private val streams: util.ArrayList[StreamSink] = new util.ArrayList[StreamSink]()
    private val canUpdate = () => hasDemand
    private var nextPublishIdx = 0

    def locateExistingSinkFor(key: StreamId): Option[StreamSink] = streamKeyToSink get key

    def addStream(key: StreamId): StreamSink = {
      closeStream(key)
      val newSink = new StreamSink(canUpdate, updateForTarget(key))
      streamKeyToSink += key -> newSink
      streams add newSink
      newSink
    }

    def closeStream(key: StreamId) = {
      streamKeyToSink get key foreach { existingSink =>
        streamKeyToSink -= key
        streams remove existingSink
      }
    }

    private def updateForTarget(key: StreamId)(tran: StreamStateTransition) = fulfillDownstreamDemandWith {
      ref.tell(StreamUpdate(key, tran), self)
      raise(Evt.StreamUpdateSent, 'stream -> key, 'target -> ref, 'payload -> tran)
    }

    def addDemand(demand: Long): Unit = {
      addConsumerDemand(demand)
      publishToAll()
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

    override val evtSource: EvtSource = parentEvtSource
  }

  private class StreamBroadcaster {
    private val sinks: util.ArrayList[StreamSink] = new util.ArrayList[StreamSink]()
    private var latestState: Option[StreamState] = None

    def state = latestState

    def removeSink(existingSink: StreamSink) = sinks remove existingSink

    def run(transition: StreamStateTransition): Boolean =
      transitionLocalStateWith(transition) match {
        case None => false
        case Some(newState) =>
          var idx = 0
          while (idx < sinks.size()) {
            sinks get idx onTransition(transition, newState)
            idx += 1
          }
          true
      }

    private def transitionLocalStateWith(transition: StreamStateTransition) = {
      if (transition applicableTo latestState)
        latestState = transition toNewStateFrom latestState
      else
        latestState = None
      latestState
    }

    def addSink(streamSink: StreamSink) = {
      sinks add streamSink
      streamSink publish latestState
    }

  }

  private class StreamSink(canUpdate: () => Boolean, update: StreamStateTransition => Unit) {
    private var pendingState: Option[StreamState] = None
    private var remoteView: Option[StreamState] = None

    def resetDownstreamView() = remoteView = None

    def publish(state: Option[StreamState]) = {
      pendingState = state
      publishPending()
    }

    def publishPending() =
      if (canUpdate()) {
        pendingState foreach updateFrom
        pendingState = None
      }

    private def updateFrom(newState: StreamState) = newState transitionFrom remoteView foreach { x =>
      update(x)
      remoteView = Some(newState)
    }

    def onTransition(transition: StreamStateTransition, newState: StreamState) =
      if (canUpdate()) {
        if (transition applicableTo remoteView) update(transition) else updateFrom(newState)
        remoteView = Some(newState)
        pendingState = None
      } else {
        pendingState = Some(newState)
      }

  }


}









