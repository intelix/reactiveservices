package rs.core.tools.metrics

import com.codahale.metrics.Meter


trait MeterSensor {
  def update(count: Long) = {}
}

object MeterSensor {
  val Disabled = new MeterSensor {}
  def apply(id: String, create: String => Meter) = new MeterSensorImpl(id, create)
}

class MeterSensorImpl(val id: String, private val create: String => Meter) extends MeterSensor with Sensor {

  private lazy val m = create(id)

  override def update(count: Long) = m.mark(count)

}
