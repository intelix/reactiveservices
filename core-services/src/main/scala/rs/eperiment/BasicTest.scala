package rs.eperiment

import experiment.BasicAnnotation
import rs.core.sysevents.CommonEvt
import rs.experiment.{CContext, Macro, WithSyseventz}

import scala.language.experimental.macros
import scala.language.implicitConversions


class C(val underlying: CContext) {

  def +(args: AnyRef*): Unit = macro Macro.error
}

object WithApply {
  val underlying = new CContext {}

  def apply(args: AnyRef*): Unit = macro Macro.error

  def withTimer(ctx: C => Unit): Unit = ctx(new C(underlying))
}

trait E {
  val level: Boolean = true
}


trait EvtX extends CommonEvt {

  @BasicAnnotation case object SomeOtherEvent extends E

  case object AndAnother


  val SomeEvent = "SomeEvent".info

  override def componentId: String = "bla"
}

object BasicTest extends WithSyseventz with App with EvtX {

  println(s"!>>>> Ready? ")
  publisher.error("1", "2")
    CContext.value = true
  publisher.error("1", "2", "3")

  publisher.error({
    println(s"!>>>> Still executed ")
    "bla"
  })

  implicit def conv(f: E): WithApply.type = WithApply

  SomeOtherEvent({
    println(s"!>>>> Still executed 2")
    "bla"
  })




  SomeOtherEvent withTimer { c =>
    c + "ok!"
  }


}
