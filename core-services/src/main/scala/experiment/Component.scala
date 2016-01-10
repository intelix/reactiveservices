package experiment

import akka.actor.ActorSystem
import experiment.ComponentEvt.SomeEvent
import rs.core.evt._


object ComponentEvt {

  case object SomeEvent extends InfoE

  case object SomeOtherEvent extends WarningE

}

object Component extends App with EvtContext {
  override val evtSource: EvtSource = "Some component"

  println("!>>>> HELLO")

  raise(SomeEvent)
  raise(SomeEvent, "k" -> 1)
  raise(SomeEvent, "k" -> 1, "a" -> true)


  val start = System.nanoTime()
  for (i <- 1 to 100000)
  raiseWith(SomeEvent, "djjj" -> "bla1") { x =>
    123
  }

  println(s"!>>>> ${((System.nanoTime() - start)/1000).toDouble / 1000} ")

/*
  raiseWithTimer(SomeEvent, "djjj" -> "bla1") { x =>
    println(s"I am here $x")
    x + ("oh" -> "bla!")
    123
  }

  raiseWithTimer(SomeEvent, "djjj" -> "bla1") { x =>
    println(s"I am here $x")
    x + ("oh" -> "bla!")
    123
  }

  raiseWithTimer(SomeEvent, "djjj" -> "bla1") { x =>
    println(s"I am here $x")
    x + ("oh" -> "bla!")
    123
  }
  raiseWithTimer(SomeEvent, "djjj" -> "bla1") { x =>
    println(s"I am here $x")
    x + ("oh" -> "bla!")
    123
  }
*/


}
