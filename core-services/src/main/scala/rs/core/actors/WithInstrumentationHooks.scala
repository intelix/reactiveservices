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
