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
import rs.core.services._
import rs.core.stream.DictionaryMapStreamState.Dictionary
import rs.core.stream.ListStreamState.{FromTail, ListSpecs}
import rs.core.stream.SetStreamState.SetSpecs
import rs.core.tools.JsonTools.jsToExtractorOps
import rs.core.{CompositeTopicKey, ServiceKey, Subject, TopicKey}

import scala.language.postfixOps

trait TradingEngineServiceEvents extends ServiceEvt {

  val IncomingTrade = "IncomingTrade".info

  override def componentId: String = "TradingEngine"
}

class TradingEngineService(id: String) extends StatelessServiceActor(id) with Terminal with TradingEngineServiceEvents {

  case class Trade(id: String, trader: String, symbol: String, price: Double, date: String)

  implicit val dictionary: Dictionary = Dictionary("symbol", "price", "available")
  implicit val setSpecs: SetSpecs = SetSpecs(allowPartialUpdates = true)
  implicit val tradeListSpecs: ListSpecs = ListSpecs(10, FromTail)

  val tradeDictionary: Dictionary = Dictionary("id", "trader", "symbol", "price", "date")

  val availableSymbols = Set("ABC", "XYZ")

  var trades: List[Trade] = List.empty

  object SymbolContainerStream extends SimpleStreamIdTemplate("symbols")
  object TradeContainerStream extends SimpleStreamIdTemplate("trades")
  object SymbolStream extends CompoundStreamIdTemplate[String]("symbol")
  object TradeStream extends CompoundStreamIdTemplate[String]("trade")

  onSubjectMapping {
    case Subject(_, TopicKey("symbols"), _) => SymbolContainerStream()
    case Subject(_, CompositeTopicKey("symbol", sym), _) => SymbolStream(sym)
    case Subject(_, TopicKey("trades"), _) => TradeContainerStream()
    case Subject(_, CompositeTopicKey("trade", tId), _) => TradeStream(tId)
  }

  onStreamActive {
    case s@SymbolContainerStream() => s !% availableSymbols
    case s@SymbolStream(sym) =>
      s !# Array(sym, 0D, false)
      subscribe(Subject("price-source", sym))
    case s@TradeContainerStream() => s !:! trades.take(10).map(_.id)
    case s@TradeStream(tId) if trades exists (_.id == tId) =>
      trades find (_.id == tId) foreach { trade => s.!#(Array(trade.id, trade.trader, trade.symbol, trade.price, trade.date))(tradeDictionary) }
  }

  onStreamPassive {
    case s@SymbolStream(sym) => unsubscribe(Subject("price-source", sym))
  }

  onDictMapRecord {
    case (Subject(ServiceKey("price-source"), TopicKey(symbol), _), map) =>
      val stream = SymbolStream(symbol)
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