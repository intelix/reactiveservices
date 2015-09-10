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
package rs.core.tools.metrics

import java.util.concurrent.TimeUnit

import com.codahale.metrics.Timer


trait TimerSensor {
  type T
  def updateMs(ms: Long) = {}
  def updateNs(ms: Long) = {}
//  def withTimer(f: => T): T = f
}

object TimerSensor {
  val Disabled = new TimerSensor {}
  def apply(id: String, create: String => Timer) = new TimerSensorImpl(id, create)
}


class TimerSensorImpl(val id: String, private val create: String => Timer)  extends TimerSensor with Sensor {

  private lazy val m = create(id)

  // implement later if you find it's needed
//  override def withTimer(f: => T): T = ???

  override def updateMs(ms: Long) = m.update(ms, TimeUnit.MILLISECONDS)
  override def updateNs(ms: Long) = m.update(ms, TimeUnit.NANOSECONDS)

}
