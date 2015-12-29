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

import akka.actor.ActorRef
import rs.core.actors.BaseActor
import rs.core.services.{MessageId, Newer, Unknown}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

trait DuplicateMessageTracker extends BaseActor {

  val dupTrackerPurgeStaleLocationsAfterMin = 60
  private val tracking: mutable.Map[ActorRef, TrackerPerDestination] = mutable.HashMap()

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    schedulePurging()
  }

  private def schedulePurging() = scheduleOnce(1 minute, Purge)

  def clearAllFor(source: ActorRef): Unit = tracking.remove(source)

  def isNotDuplicate(sender: ActorRef, groupId: String, id: MessageId): Boolean = {
    val tracker = tracking getOrElse(sender, {
      val newTracking = new TrackerPerDestination()
      tracking.put(sender, newTracking)
      newTracking
    })
    tracker.isValid(groupId, id)
  }


  onMessage {
    case Purge =>
      tracking.filter(_._2.isStale).keys.foreach(tracking.remove)
      schedulePurging()
  }

  // do not explicitly watch, but hook into onTerminated. Let trait consumer define the rules
  onActorTerminated { ref =>
    clearAllFor(ref)
  }

  private class TrackerPerDestination {
    var lastActivity = now
    var groups: mutable.Map[String, MessageId] = mutable.HashMap()

    def isStale = now - lastActivity > dupTrackerPurgeStaleLocationsAfterMin * 60 * 1000

    def isValid(groupId: String, id: MessageId) = {
      lastActivity = now
      groups.get(groupId) match {
        case Some(lastId) => id compareWith lastId match {
          case Newer | Unknown =>
            groups.put(groupId, id)
            true
          case _ => false
        }
        case _ => true
      }
    }

  }

  private case object Purge

}
