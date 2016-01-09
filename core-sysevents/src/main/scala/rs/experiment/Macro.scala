package rs.experiment

import scala.reflect.macros.blackbox

object Macro {
  type MyContext = blackbox.Context { type PrefixType = MacroContext }

  def error(c: MyContext)(args: c.Expr[AnyRef]*) = {
    import c.universe._
    val underlying = q"${c.prefix}.underlying"
      q"if ($underlying.isOk) $underlying.run(..$args)"
  }

}
