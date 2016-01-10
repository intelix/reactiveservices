package experiment

import experiment.ComponentEvt.SomeEvent
import rs.core.evt._

// TODO !>>>> REMOVE


object ComponentEvt {

  case object SomeEvent extends InfoE

  case object SomeOtherEvent extends WarningE

}

object Component extends App with EvtContext {
  override val evtSource: EvtSource = "Some component"


  raise(SomeEvent)
  raise(SomeEvent, 'k -> 1)
  raise(SomeEvent, 'k -> 1, 'a -> true)




  raiseWithTimer(SomeEvent, 'a -> "1") { x =>
    println(s"Thunk executed")
    x + ('b -> "2")
    123
  }


}
