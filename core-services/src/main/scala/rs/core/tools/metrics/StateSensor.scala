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

trait StateSensor {
  def update(s: String) = {}
}

object StateSensorConstants {
  val StdStateUnknown = "Unknown"
  val StdStateActive = "Active"
  val StdStatePassive = "Passive"
}

object StateSensor {
  val Disabled = new StateSensor {}
  def apply(id: String, create: String => StatePublisher) = new StateSensorImpl(id, create)

}


class StateSensorImpl(val id: String, private val create: String => StatePublisher)  extends StateSensor with Sensor {

  private lazy val p = create(id)

  override def update(s: String) = p.update(s)

}
