package rs.experiment

trait WithSyseventz {

  protected implicit val publisher: MacroContext = MacroContext(new CContext {})

}
