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

import java.util.Date

import play.api.libs.json.Json
import rs.core.SubjectTags.UserId
import rs.core.services.endpoint.Terminal
import rs.core.services.{ServiceCell, ServiceCellSysevents, StreamId}
import rs.core.stream.DictionaryMapStreamState.Dictionary
import rs.core.stream.ListStreamState.{FromTail, ListSpecs}
import rs.core.stream.SetStreamState.SetSpecs
import rs.core.tools.JsonTools.jsToExtractorOps
import rs.core.{CompositeTopicKey, ServiceKey, Subject, TopicKey}

import scala.language.postfixOps

trait TradingEngineServiceEvents extends ServiceCellSysevents {

  val IncomingTrade = "IncomingTrade".info

  override def componentId: String = "TradingEngine"
}

class TradingEngineService(id: String) extends ServiceCell(id) with Terminal with TradingEngineServiceEvents {

  case class Trade(id: String, trader: String, symbol: String, price: Double, date: String)

  implicit val dictionary: Dictionary = Dictionary("symbol", "price", "available")
  implicit val setSpecs: SetSpecs = SetSpecs(allowPartialUpdates = true)
  implicit val tradeListSpecs: ListSpecs = ListSpecs(10, FromTail)

  val tradeDictionary: Dictionary = Dictionary("id", "trader", "symbol", "price", "date")


  val availableSymbols = Set("ABC", "XYZ")

  var trades: List[Trade] = List.empty

  onSubjectMapping {
    case Subject(_, TopicKey("symbols"), _) => Some("symbols")
    case Subject(_, CompositeTopicKey("symbol", sym), _) => Some(("symbol", sym))
    case Subject(_, TopicKey("trades"), _) => Some("trades")
    case Subject(_, CompositeTopicKey("trade", tId), _) => Some(("trade", tId))
  }

  onStreamActive {
    case StreamId("symbols", _) => "symbols" !% availableSymbols
    case s@StreamId("symbol", Some(sym: String)) =>
      s !# Array(sym, 0D, false)
      subscribe(Subject("price-source", sym))
    case StreamId("trades", _) => "trades" !:! trades.take(10).map(_.id)
    case s@StreamId("trade", Some(tId: String)) if trades exists (_.id == tId) =>
      trades find (_.id == tId) foreach { trade => s.!#(Array(trade.id, trade.trader, trade.symbol, trade.price, trade.date))(tradeDictionary) }
  }

  onStreamPassive {
    case s@StreamId("symbol", Some(sym: String)) =>
      unsubscribe(Subject("price-source", sym))
  }

  onDictMapRecord {
    case (Subject(ServiceKey("price-source"), TopicKey(symbol), _), map) =>
      val stream = StreamId("symbol", Some(symbol))
      map.get("price", 0D) match {
        case 0 => stream !# Array(symbol, 0D, false)
        case price => stream !# Array(symbol, price, true)
      }
  }

  onSignal {
    case (Subject(_, TopicKey("trade"), UserId(uid)), v: String) => IncomingTrade { ctx =>
      for (
        json <- Some(Json.parse(v));
        sym <- json ~> 'sym;
        price <- json +&> 'price
      ) yield {
        val tradeId = (trades.size + 1).toString
        trades +:= Trade(tradeId, uid, sym, price, new Date().toString)
        "trades" !:+(0, tradeId)
        ctx +('id -> tradeId, 'executed -> true, 'symbol -> sym, 'price -> price, 'trader -> uid)
        SignalOk()
      }
    }
  }

}