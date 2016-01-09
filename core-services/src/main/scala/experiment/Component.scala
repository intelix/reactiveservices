package experiment

import experiment.ComponentEvt.SomeEvent
import rs.core.evt._


object ComponentEvt  {
  case object SomeEvent extends InfoE
  case object SomeOtherEvent extends WarningE
}

object Component extends App with EvtContext {
  override val evtSource: EvtSource = "Some component"

  println("!>>>> HELLO")

  raise(SomeEvent)
  raise(SomeEvent, "k" -> 1)
  raise(SomeEvent, "k" -> 1, "a" -> true)



    run(SomeEvent, "jjj" -> "bla1"){ x =>
      println(s"I am here $x")
      x + ("oh" -> "bla!")
    }

}
