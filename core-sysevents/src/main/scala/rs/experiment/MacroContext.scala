package rs.experiment

import scala.language.experimental.macros

object MacroContext {

  def apply(underlying: CContext): MacroContext = new MacroContext(underlying)

}


final class MacroContext private(var underlying: CContext) {

  def error(args: AnyRef*): Unit = macro Macro.error

}