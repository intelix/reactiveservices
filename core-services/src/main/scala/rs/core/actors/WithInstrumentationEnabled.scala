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

import rs.core.tools.metrics._

import scalaz.Scalaz._

trait WithInstrumentationEnabled extends WithInstrumentationHooks with WithCHMetrics {

  private lazy val prefix =
    (sensorHostId | "_") + "~" +
      (sensorSystemId | "_") + "~" +
      (sensorComponentId | "_") + "~"


  private var localSensors: Map[String, Sensor] = Map()

  final override def destroySensors() = {
    localSensors.keys.foreach(destroySensor)
    localSensors = Map()
  }

  def sensorHostId: Option[String]

  def sensorSystemId: Option[String]

  def sensorComponentId: Option[String]

  override def meterSensor(group: Option[String], name: String) = locateOrCreate(group, name, id => MeterSensor(id, metricRegistry.meter))

  override def histogramSensor(group: Option[String], name: String) = locateOrCreate(group, name, id => HistogramSensor(id, metricRegistry.histogram))

  override def timerSensor(group: Option[String], name: String) = locateOrCreate(group, name, id => TimerSensor(id, metricRegistry.timer))

  override def stateSensor(group: Option[String], name: String) = locateOrCreate(group, name, id => StateSensor(id, SimpleStateRegistry.getPublisherFor))

  private def locateOrCreate[T <: Sensor](group: Option[String], name: String, create: String => T) = {
    val fullName = fullSensorName(group, name)
    localSensors.get(fullName) match {
      case Some(m) => m.asInstanceOf[T]
      case None =>
        EventstreamsSensorRegistry.registerSharedSensor(fullName)
        val m = create(fullName)
        localSensors += fullName -> m
        m
    }
  }

  private def fullSensorName(group: Option[String], metric: String) = prefix + (group.map(_ + ".") | "") + metric

  private def destroySensor(id: String) = {
    EventstreamsSensorRegistry.unregisterSharedSensor(id)
    SimpleStateRegistry.remove(id)
  }

}
