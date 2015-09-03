package rs.core.services.internal

import akka.actor.ActorRef
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.{MessageId, Newer, Unknown}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

trait DuplicateMessageTracker extends ActorWithComposableBehavior {

  private case object Purge

  private val tracking: mutable.Map[ActorRef, TrackerPerDestination] = mutable.HashMap()

  val dupTrackerPurgeStaleLocationsAfterMin = 60


  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    schedulePurging()
  }

  private def schedulePurging() = scheduleOnce(1 minute, Purge)


  onMessage {
    case Purge =>
      tracking.filter(_._2.isStale).keys.foreach(tracking.remove)
      schedulePurging()
  }

  // do not explitly watch, but hook into onTerminated. Let trait user define the rules
  override def onTerminated(ref: ActorRef): Unit = {
    clearAllFor(ref)
    super.onTerminated(ref)
  }

  def clearAllFor(source: ActorRef): Unit = tracking.remove(source)

  def isNotDuplicate(sender: ActorRef, groupId: String, id: MessageId): Boolean = {
    val tracker = tracking getOrElse(sender, {
      val newTracking = new TrackerPerDestination()
      tracking.put(sender, newTracking)
      newTracking
    })
    logger.info(s"!>>>> Dup checking $groupId, $id, $sender = " + tracker.isValid(groupId, id))
    tracker.isValid(groupId, id)
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

}
