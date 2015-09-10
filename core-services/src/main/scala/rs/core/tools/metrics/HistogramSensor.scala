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

import com.codahale.metrics.Histogram

trait HistogramSensor {
  def update(value : scala.Long) = {}
  def update(value : scala.Int) = {}
}

object HistogramSensor {
  val Disabled = new HistogramSensor {}
  def apply(id: String, create: String => Histogram) = new HistogramSensorImpl(id, create)
}



class HistogramSensorImpl(val id: String, private val create: String => Histogram)  extends HistogramSensor with Sensor {

  private lazy val m = create(id)

  override def update(value : scala.Long) = m.update(value)
  override def update(value : scala.Int) = m.update(value)

}
