package au.com.intelix.essentials.time

case class MsTimer(ms: Long = System.currentTimeMillis()) {
  private def diff = System.currentTimeMillis() - ms
  def toMillis = diff
}
case class NanoTimer(nano: Long = System.nanoTime()) {
  private def diff = System.nanoTime() - nano
  def toMillis = (diff / 1000).toDouble / 1000
}
