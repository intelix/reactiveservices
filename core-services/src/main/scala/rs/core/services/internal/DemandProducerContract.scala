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
package rs.core.services.internal

import java.util

import akka.actor.ActorRef
import rs.core.config.ConfigOps.wrap
import rs.core.services.SequentialMessageIdGenerator
import rs.core.services.internal.InternalMessages.DownstreamDemandRequest
import rs.core.sysevents.CommonEvt

import scala.collection.mutable


trait DemandProducerContractEvt extends CommonEvt {

  val StartedDemandProducer = "StartedDemandProducer".trace
  val StoppedDemandProducer = "StoppedDemandProducer".trace

}

trait DemandProducerContract extends SimpleInMemoryAcknowledgedDelivery with DemandProducerContractEvt {

  private val idGenerator = new SequentialMessageIdGenerator()

  private val HighWatermark = nodeCfg.asInt("service-port.backpressure.high-watermark", 500)
  private val LowWatermark = nodeCfg.asInt("service-port.backpressure.low-watermark", 100)

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
      StartedDemandProducer('target -> ref, 'seed -> idGenerator.seed)
      val contract = new LocalDemand(ref, withAcknowledgedDelivery)
      pending += ref -> contract
      pendingList add contract
    }
  }

  override def preStart(): Unit = {
    super.preStart()
    checkDemand()
  }

  def checkDemand() = {
    var idx = 0
    while (idx < pendingList.size()) {
      val nextContract = pendingList get idx
      nextContract check()
      idx += 1
    }
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
      if (demand <= LowWatermark) {
        val newDemand = HighWatermark - demand
        val msg = DownstreamDemandRequest(idGenerator.next(), newDemand)
        if (withAcknowledgedDelivery)
          acknowledgedDelivery(ref, msg, SpecificDestination(ref))
        else ref ! msg
        demand = HighWatermark
      }

  }

}
