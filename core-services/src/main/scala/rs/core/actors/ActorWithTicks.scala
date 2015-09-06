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

package rs.core.actors

import scala.concurrent.duration._

private case class CallbackRequest(f: () => Unit, intervalMs: Long, lastCallTs: Long)

trait ActorWithTicks extends ActorWithComposableBehavior {

  implicit private val ec = context.dispatcher

  private var callbacks: List[CallbackRequest] = List()
  private var callbacksOnEveryTick: List[() => Unit] = List()

  def tickInterval = 1.second

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    scheduleTick()
    super.preStart()
  }

  def internalProcessTick(): Unit = {}
  def processTick(): Unit = {}

  def onTick(intervalMs: Long)(callback: => Unit): Unit = callbacks = callbacks :+ CallbackRequest(() => callback, intervalMs, 0)
  def onTick(callback: => Unit): Unit = callbacksOnEveryTick = callbacksOnEveryTick :+ (() => callback)

  private def scheduleTick() = this.context.system.scheduler.scheduleOnce(tickInterval, self, Tick())(context.dispatcher)

  onMessage {
    case Tick() =>
      processCallbacks()
      internalProcessTick()
      processTick()
      scheduleTick()
  }

  private def processCallbacks() = {
    if (callbacks.nonEmpty)
      callbacks = callbacks.map {
        case CallbackRequest(f, i, l) if now - l >= i =>
          f()
          CallbackRequest(f, i, now)
        case x => x
      }
    if (callbacksOnEveryTick.nonEmpty)
      callbacksOnEveryTick foreach(_())
  }

  private case class Tick()


}
