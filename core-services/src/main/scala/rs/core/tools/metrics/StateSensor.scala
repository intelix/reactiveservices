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
