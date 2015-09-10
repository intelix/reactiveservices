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

import rs.core.tools.metrics.{HistogramSensor, MeterSensor, StateSensor, TimerSensor}


trait WithInstrumentationHooks {

  def meterSensor(name: String): MeterSensor = meterSensor(None, name)

  def meterSensor(group: Option[String], name: String): MeterSensor = MeterSensor.Disabled

  def histogramSensor(name: String): HistogramSensor = histogramSensor(None, name)

  def histogramSensor(group: Option[String], name: String): HistogramSensor = HistogramSensor.Disabled

  def timerSensor(name: String): TimerSensor = timerSensor(None, name)

  def timerSensor(group: Option[String], name: String): TimerSensor = TimerSensor.Disabled

  def stateSensor(name: String): StateSensor = stateSensor(None, name)

  def stateSensor(group: Option[String], name: String): StateSensor = StateSensor.Disabled

  def destroySensors(): Unit = {}

}
