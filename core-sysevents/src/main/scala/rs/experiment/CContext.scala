package rs.experiment


object CContext {
  var value = false
}

trait CContext {

  def isOk = CContext.value
  def run(a: AnyRef*) = println(s"!>>>> CContext: $a ")

}
