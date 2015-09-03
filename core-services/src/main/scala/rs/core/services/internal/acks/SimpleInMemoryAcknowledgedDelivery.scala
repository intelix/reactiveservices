package rs.core.services.internal.acks

import java.util

import akka.actor.ActorRef
import rs.core.actors.ActorWithTicks
import rs.core.services.{Expirable, MessageId, SequentialMessageIdGenerator}


trait DestinationRoute

trait SimpleInMemoryAckedDeliveryWithDynamicRouting extends SimpleInMemoryAcknowledgedDelivery {

  case class LogicalDestination(route: String) extends DestinationRoute

  def resolveLogicalRoute(routeId: String): Option[ActorRef]

  override def resolveRoute(id: DestinationRoute): Option[ActorRef] = id match {
    case LogicalDestination(routeId) => resolveLogicalRoute(routeId)
    case _ => super.resolveRoute(id)
  }
}

trait SimpleInMemoryAcknowledgedDelivery extends ActorWithTicks {

  case class SpecificDestination(ref: ActorRef) extends DestinationRoute


  type MessageSelection = Any => Boolean

  private val SharedGroup = None
  private val groups: util.ArrayList[OrderedGroup] = new util.ArrayList[OrderedGroup]()
  private var groupsMap: Map[GroupId, OrderedGroup] = Map()
  private var pendingOrderedDeliveries: Map[MessageId, OrderedGroup] = Map.empty
  private var pendingUnorderedDeliveries: Map[MessageId, DeliveryInfo] = Map.empty

  private val messageIdGenerator = new SequentialMessageIdGenerator()


  def resolveRoute(id: DestinationRoute): Option[ActorRef] = id match {
    case SpecificDestination(ref) => Some(ref)
    case _ => None
  }

  def cancelMessages(route: DestinationRoute): Unit = {
    groupsMap foreach {
      case (id, grp) if id.route == route => grp cancelAll()
      case _ =>
    }
    pendingUnorderedDeliveries = pendingUnorderedDeliveries filter {
      case (_, di) => di.route != route
    }

  }

  def cancelMessages(key: Any, route: DestinationRoute, selection: MessageSelection): Unit =
    cancelMessages(GroupId(key, route), selection)


  def cancelMessages(id: GroupId, selection: MessageSelection): Unit = {
    groupsMap get id foreach { grp =>
      grp cancel selection
    }
  }

  def cancelMessages(selection: MessageSelection): Unit = {
    groupsMap foreach {
      case (_, grp) => grp cancel selection
    }
    pendingUnorderedDeliveries = pendingUnorderedDeliveries filter {
      case (_, di) => !selection(di.msg.payload)
    }
  }


  def unorderedAcknowledgedDelivery(msg: Any, route: DestinationRoute)(implicit sender: ActorRef): Unit = {
    val ackTo = if (sender == self) None else Some(self)

    val ackMsg = Acknowledgeable(messageIdGenerator.next(), msg, ackTo)

    val di = DeliveryInfo(ackMsg, sender, 0, route, None, 0)

    logger.info("!>>>> Unordered delivery of " + ackMsg)

    pendingUnorderedDeliveries += ackMsg.messageId -> process(di)

  }


  def acknowledgedDelivery(orderedGroupId: Any, msg: Any, route: DestinationRoute, cancelWithSelection: Option[MessageSelection] = None)(implicit sender: ActorRef): Unit = {

    val id = GroupId(orderedGroupId, route)

    cancelWithSelection foreach (cancelMessages(id, _))

    val group = groupsMap getOrElse(id, newOrderedGroup(id))

    val ackTo = if (sender == self) None else Some(self)

    group.deliver(Acknowledgeable(messageIdGenerator.next(), msg, ackTo), sender)
  }

  def cancelDelivery(id: MessageId) = markAsDelivered(id)

  override def processTick(): Unit = {
    processQueue()
    super.processTick()
  }

  def totalAcknowledgedDeliveryInflights = {
    def totalEnqueued = {
      var idx = 0
      var cnt = 0
      while (idx < groups.size()) {
        val nextGrp = groups.get(idx)
        cnt += nextGrp.totalEnqueued
        idx += 1
      }
      cnt
    }
    totalEnqueued + pendingOrderedDeliveries.size + pendingUnorderedDeliveries.size
  }


  def processQueue() = {
    var forRemoval: Seq[OrderedGroup] = Seq()
    var idx = 0
    while (idx < groups.size()) {
      val nextGroup = groups get idx
      if (nextGroup.shouldBeRemoved)
        forRemoval = forRemoval :+ nextGroup
      else
        nextGroup processMessages()
      idx += 1
    }
    if (forRemoval.nonEmpty) forRemoval foreach remove
    pendingUnorderedDeliveries = pendingUnorderedDeliveries map {
      case (k, v) => k -> process(v)
    }
  }

  private def remove(g: OrderedGroup) = {
    groups remove g
    groupsMap -= g.id
  }

  private def newOrderedGroup(id: GroupId) = {
    val group = new OrderedGroup(id)
    groupsMap += id -> group
    groups.add(group)
    group
  }

  private def markAsDelivered(id: MessageId) = {
    pendingOrderedDeliveries get id foreach (_.ack())
    pendingUnorderedDeliveries -= id
  }


  onMessage {
    case Acknowledgement(id) if pendingOrderedDeliveries.contains(id) || pendingUnorderedDeliveries.contains(id) => markAsDelivered(id)
  }


  // TODO configurable interval
  private def process(info: DeliveryInfo): DeliveryInfo =
    if (now - info.sent > 3000) {
      resolveRoute(info.route) match {
        case None if info.sent > 0 => info.copy(sent = 0, sentTo = None)
        case None => info
        case location@Some(ref) =>
          ref.tell(info.msg, info.sender)
          logger.info(s"!>>> Sent ${info.msg} [${info.msg.messageId}] -> ${info.route} -> $ref")
          info.copy(sent = now, attempts = info.attempts + 1, sentTo = location)
      }
    } else info


  private case class GroupId(key: Any, route: DestinationRoute)

  private case class DeliveryInfo(msg: Acknowledgeable, sender: ActorRef, sent: Long, route: DestinationRoute, sentTo: Option[ActorRef], attempts: Int)

  private class OrderedGroup(val id: GroupId) {

    def cancelAll() = {
      queue = List()
      currentDelivery foreach { cd =>
        pendingOrderedDeliveries -= cd.msg.messageId
        currentDelivery = None
      }
      idleSince = now
    }

    def cancel(selection: (Any) => Boolean) = {
      queue = queue filterNot { x => selection(x.msg.payload) }
      currentDelivery = currentDelivery match {
        case Some(t: DeliveryInfo) if selection(t.msg.payload) =>
          pendingOrderedDeliveries -= t.msg.messageId
          None
        case x => x
      }
      processMessages()
    }


    private var queue: List[DeliveryInfo] = List()
    private var currentDelivery: Option[DeliveryInfo] = None
    private var idleSince: Long = -1

    def totalEnqueued = queue.size

    def clearCurrentDelivery() =
      currentDelivery foreach { d =>
        pendingOrderedDeliveries -= d.msg.messageId
        currentDelivery = None
      }

    def ack(): Unit = {
      clearCurrentDelivery()
      processMessages()
    }

    private def proceedToNextMessage() = {
      clearCurrentDelivery()
      queue = queue.filter(_.msg.payload match {
        case t: Expirable => t.expireAt > now
        case _ => true
      })
      if (queue.isEmpty)
        idleSince = now
      else {
        val next = queue.head
        queue = queue.tail
        deliverNext(next)
      }
    }

    def processMessages() = {
      currentDelivery match {
        case Some(x@DeliveryInfo(t: Expirable, _, _, _, _, _)) if t.expireAt < now => proceedToNextMessage()
        case Some(x) => currentDelivery = Some(process(x))
        case None if queue.isEmpty && idleSince < 1 => idleSince = now
        case _ => proceedToNextMessage()
      }
    }

    def deliver(msg: Acknowledgeable, sender: ActorRef) = {
      val di = DeliveryInfo(msg, sender, 0, id.route, None, 0)
      currentDelivery match {
        case None => deliverNext(di)
        case _ => scheduleNext(di)
      }
      idleSince = -1
    }

    def shouldBeRemoved = idleSince > -1 && now - idleSince > 5000

    private def scheduleNext(msg: DeliveryInfo) = queue = queue :+ msg

    private def deliverNext(msg: DeliveryInfo) = {
      currentDelivery = Some(process(msg))
      pendingOrderedDeliveries += msg.msg.messageId -> this
    }


  }


}
