package rs.core.services.internal

import java.util

import akka.actor.ActorRef
import rs.core.actors.ActorWithTicks
import rs.core.services.SequentialMessageIdGenerator
import rs.core.services.internal.InternalMessages.DownstreamDemandRequest
import rs.core.sysevents.ref.ComponentWithBaseSysevents

import scala.collection.mutable


trait DemandProducerContractSysevents extends ComponentWithBaseSysevents {

  val StartedDemandProducer = "StartedDemandProducer".info
  val StoppedDemandProducer = "StoppedDemandProducer".info

}

trait DemandProducerContract extends SimpleInMemoryAcknowledgedDelivery with ActorWithTicks with DemandProducerContractSysevents {

  val idGenerator = new SequentialMessageIdGenerator()

  val requestAtOnce = 1500
  val requestMoreAt = 500

  private val targets: Set[ActorRef] = Set.empty
  private val pending: mutable.Map[ActorRef, LocalDemand] = mutable.HashMap()
  private val pendingList: util.ArrayList[LocalDemand] = new util.ArrayList[LocalDemand]()

  def cancelDemandProducerFor(ref: ActorRef) = {
    pending get ref foreach { c =>
      StoppedDemandProducer('target -> ref)
      pending -= ref
      pendingList remove c
    }
  }

  def cancelAllDemandProducers(): Unit = {
    StoppedDemandProducer('target -> "all")
    pending clear()
    pendingList clear()
  }

  def startDemandProducerFor(ref: ActorRef, withAcknowledgedDelivery: Boolean) = {
    if (!pending.contains(ref)) {
      StartedDemandProducer('target -> ref)
      val contract = new LocalDemand(ref, withAcknowledgedDelivery)
      pending += ref -> contract
      pendingList add contract
    }
  }

  def checkDemand() = {
    var idx = 0
    while (idx < pendingList.size()) {
      val nextContract = pendingList get idx
      nextContract check()
      idx += 1
    }
  }

  override def preStart(): Unit = {
    super.preStart()
    checkDemand()
  }

  onTick {
    checkDemand()
  }

  def upstreamDemandFulfilled(id: ActorRef, c: Int) =
    pending get id foreach { d =>
      d.dec()
      d.check()
    }

  private class LocalDemand(val ref: ActorRef, withAcknowledgedDelivery: Boolean) {
    private var demand = 0L

    def dec() = demand -= 1

    def hasDemand = demand > 0

    def check() =
      if (demand <= requestMoreAt) {
        val newDemand = requestAtOnce - demand
        val msg = DownstreamDemandRequest(idGenerator.next(), newDemand)
        if (withAcknowledgedDelivery)
          acknowledgedDelivery(ref, msg, SpecificDestination(ref))
        else ref ! msg
        demand = requestAtOnce
      }

  }

}
