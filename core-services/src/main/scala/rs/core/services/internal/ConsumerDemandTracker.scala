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

import rs.core.sysevents.{EvtPublisherContext, CommonEvt}

trait ConsumerDemandTrackerEvt extends CommonEvt


trait ConsumerDemandTracker extends ConsumerDemandTrackerEvt {

  self: EvtPublisherContext =>

  @volatile var currentDemand = 0L

  def addConsumerDemand(count: Long) = currentDemand += count

  def hasDemand = currentDemand > 0

  def fulfillDownstreamDemandWith(f: => Unit) = {
    if (hasDemand) {
      f
      currentDemand -= 1
    }
  }
}