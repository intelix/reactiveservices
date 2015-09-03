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
