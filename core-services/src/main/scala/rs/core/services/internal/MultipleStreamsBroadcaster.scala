package rs.core.services.internal

import java.util

import akka.actor.ActorRef
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.internal.InternalMessages.StreamUpdate
import rs.core.stream.{StreamState, StreamStateTransition}

import scala.collection.mutable

class StreamBroadcaster {
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

  def addSink(streamSink: StreamSink) = {
    sinks add streamSink
    streamSink publish latestState
  }

  private def transitionLocalStateWith(transition: StreamStateTransition) = {
    if (transition applicableTo latestState)
      latestState = transition toNewStateFrom latestState
    else
      latestState = None
    latestState
  }

}

class StreamSink(canUpdate: () => Boolean, update: StreamStateTransition => Unit) {
  private var pendingState: Option[StreamState] = None
  private var remoteView: Option[StreamState] = None

  def resetDownstreamView() = remoteView = None

  def publish(state: Option[StreamState]) = {
    pendingState = state
    publishPending()
  }

  def publishPending() = {
    println(s"!>>>  ---------------")
    println(s"!>>>       pending state: $pendingState")
    println(s"!>>>       latest state: $remoteView")
    println(s"!>>>       can update: " + canUpdate())
    if (canUpdate()) {
      pendingState foreach updateFrom
      pendingState = None
    }
  }

  def onTransition(transition: StreamStateTransition, newState: StreamState) =
    if (canUpdate()) {
      if (transition applicableTo remoteView) update(transition) else updateFrom(newState)
      remoteView = Some(newState)
      pendingState = None
    } else {
      pendingState = Some(newState)
    }

  private def updateFrom(newState: StreamState) = newState transitionFrom remoteView foreach update

}

trait MultipleStreamsBroadcaster {
  this: ActorWithComposableBehavior =>
  private val targets: mutable.Map[ActorRef, ConsumerWithStreamSinks] = mutable.HashMap()
  private val streams: mutable.Map[StreamId, StreamBroadcaster] = mutable.HashMap()

  def stateOf(key: StreamId): Option[StreamState] = streams get key flatMap (_.state)

  def newConsumerDemand(consumer: ActorRef, demand: Long): Unit = targets get consumer foreach (_.addDemand(demand))

  def stateTransitionFor(key: StreamId, transition: => StreamStateTransition): Boolean = {
    logger.info(s"!>>>> stateTransitionFor($key, $transition)")
    val result = streams get key forall (_.run(transition))
    result
  }

  def initiateTarget(ref: ActorRef) = {
    targets getOrElse(ref, newTarget(ref))
  }

  def initiateStreamFor(ref: ActorRef, key: StreamId) = {
    val target = targets getOrElse(ref, newTarget(ref))
    val stream = streams getOrElse(key, newStreamBroadcaster(key))

    val sink = target locateExistingSinkFor key match {
      case None =>
        val newSink = target addStream key
        stream addSink newSink
        newSink
      case Some(s) => s
    }
    sink.resetDownstreamView()

  }

  def closeStreamFor(ref: ActorRef, key: StreamId) = {
    targets get ref foreach { target =>
      target locateExistingSinkFor key foreach { existingSink =>
        target.closeStream(key)
        streams get key foreach { stream =>
          stream removeSink existingSink
        }
      }
    }
  }

  private def newStreamBroadcaster(key: StreamId) = {
    val sb = new StreamBroadcaster()
    streams += key -> sb
    sb
  }

  private def newTarget(ref: ActorRef) = {
    logger.info(s"!>>>> New target: $ref")
    val target = new ConsumerWithStreamSinks(ref, self)
    targets += ref -> target
    target
  }


}


class ConsumerWithStreamSinks(val ref: ActorRef, self: ActorRef) extends ConsumerDemandTracker {
  private val streamKeyToSink: mutable.Map[StreamId, StreamSink] = mutable.HashMap()
  private val streams: util.ArrayList[StreamSink] = new util.ArrayList[StreamSink]()
  private val canUpdate = () => hasDemand
  private var nextPublishIdx = 0

  def locateExistingSinkFor(key: StreamId): Option[StreamSink] = streamKeyToSink get key

  def closeStream(key: StreamId) = {
    streamKeyToSink get key foreach { existingSink =>
      streamKeyToSink -= key
      streams remove existingSink
    }
  }

  def addStream(key: StreamId): StreamSink = {
    closeStream(key)
    val newSink = new StreamSink(canUpdate, updateForTarget(key))
    streamKeyToSink += key -> newSink
    streams add newSink
    newSink
  }

  def addDemand(demand: Long): Unit = {
    println(s"!>>> new demand for $ref, was: $currentDemand adding $demand")
    addConsumerDemand(demand)
    publishToAll()
  }

  private def updateForTarget(key: StreamId)(tran: StreamStateTransition) = fulfillDownstreamDemandWith {
    println(s"!>>> updateForTarget $key -> $ref : " + tran)
    ref.tell(StreamUpdate(key, tran), self)
  }

  private def publishToAll() = if (streams.size() > 0) {
    val cycles = streams.size()
    var cnt = 0
    while (cnt < cycles && hasDemand) {
      println(s"!>>>> publishToAll($nextPublishIdx) ")
      streams get nextPublishIdx publishPending()
      nextPublishIdx += 1
      if (nextPublishIdx == streams.size()) nextPublishIdx = 0
      cnt += 1
    }
  }

}


trait ConsumerDemandTracker {
  var currentDemand = 0L

  def addConsumerDemand(count: Long) = {
    println(s"!>>> ${this.getClass.getSimpleName}] : Adding demand.... $currentDemand  +  $count")
    currentDemand += count
  }

  def hasDemand = currentDemand > 0

  def fulfillDownstreamDemandWith(f: => Unit) = {
    if (hasDemand) {
      println(s"!>>> ${this.getClass.getSimpleName}] : Fulfilling demand.... $currentDemand")
      f
      currentDemand -= 1
    } else {
      println(s"!>>> NO DEMAND!")
    }
  }
}





