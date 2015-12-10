/*
 * Copyright 2014-15 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rs.examples.stocks

import rs.core.services.{ServiceEvt, SimpleStreamId, StatelessServiceActor, StreamId}
import rs.core.stream.DictionaryMapStreamState.Dictionary
import rs.core.{Subject, TopicKey}

import scala.concurrent.duration._
import scala.language.postfixOps

trait PriceSourceServiceEvt extends ServiceEvt {
  override def componentId: String = "PriceSourceService"
}

class PriceSourceService(id: String) extends StatelessServiceActor(id) with PriceSourceServiceEvt {

  implicit val dict = Dictionary("price")

  var symbols: Map[String, Int] = Map.empty

  onSubjectMapping {
    case Subject(_, TopicKey(sym), _) => sym
  }

  onStreamActive {
    case SimpleStreamId(sym) => symbols += sym -> (Math.random() * 5000).toInt
  }

  onStreamPassive {
    case SimpleStreamId(sym) => symbols -= sym
  }


  onMessage {
    case "tick" =>
      symbols.foreach {
        case (sym, base) =>
          val price = (base + ((Math.random() * 500).toInt - 250)).toDouble / 100
          sym !# ("price" -> price)
      }
      scheduleOnce(2 seconds, "tick")
  }

  scheduleOnce(2 seconds, "tick")

}
