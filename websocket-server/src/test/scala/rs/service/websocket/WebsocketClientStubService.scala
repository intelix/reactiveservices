/*
 * Copyright 2014-16 Intelix Pty Ltd
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
package rs.service.websocket

import akka.actor.{ActorRef, FSM, Props, Stash}
import akka.io.IO
import akka.util.{ByteIterator, ByteString}
import rs.core.Subject
import rs.core.actors.{ActorState, StatefulActor}
import rs.core.codec.binary.BinaryProtocolMessages._
import rs.core.services.endpoint.StreamConsumer
import rs.core.services.{ServiceEvt, StatelessServiceActor}
import rs.core.stream._
import rs.service.websocket.WebSocketClient.{Connecting, Established, WebsocketConnection}
import rs.service.websocket.WebsocketClientStubService._
import spray.can.Http.Connect
import spray.can.server.UHttp
import spray.can.websocket.frame.{BinaryFrame, TextFrame}
import spray.can.{Http, websocket}
import spray.http.{HttpHeaders, HttpMethods, HttpRequest, HttpResponse}

import scala.annotation.tailrec
import scala.language.postfixOps


trait WebsocketClientStubServiceEvt extends ServiceEvt {
  val ConnectionUpgraded = "ConnectionUpgraded".info
  val ConnectionEstablished = "ConnectionEstablished".info
  val ConnectionClosed = "ConnectionClosed".info

  val ReceivedPing = "ReceivedPing".info
  val ReceivedServiceNotAvailable = "ReceivedServiceNotAvailable".info
  val ReceivedInvalidRequest = "ReceivedInvalidRequest".info
  val ReceivedSubscriptionClosed = "ReceivedSubscriptionClosed".info
  val ReceivedStreamStateUpdate = "ReceivedStreamStateUpdate".info
  val ReceivedStreamStateTransitionUpdate = "ReceivedStreamStateTransitionUpdate".info
  val ReceivedSignalAckOk = "ReceivedSignalAckOk".info
  val ReceivedSignalAckFailed = "ReceivedSignalAckFailed".info

  val StringUpdate = "StringUpdate".info
  val SetUpdate = "SetUpdate".info
  val MapUpdate = "MapUpdate".info
  val ListUpdate = "ListUpdate".info


  override def componentId: String = "WebsocketClientStubService"
}

object WebsocketClientStubServiceEvt extends WebsocketClientStubServiceEvt

object WebsocketClientStubService {

  case class StartWebsocketClient(id: String, host: String, port: Int)

  case class OpenSubscriptionFromStub(subj: Subject, priorityKey: Option[String] = None, aggregationIntervalMs: Int = 0)

  case class CloseSubscriptionFromStub(subj: Subject)

  case class ResetSubscriptionFromStub(subj: Subject)

  case class SignalFromStub(subj: Subject, payload: Any, expireAt: Long, orderingGroup: Option[Any], correlationId: Option[Any])

}

class WebsocketClientStubService(serviceId: String) extends StatelessServiceActor(serviceId) {

  onMessage {
    case StartWebsocketClient(id, host, port) => context.actorOf(Props(classOf[WebSocketClient], id, host, port), id)
  }

  override def componentId: String = "WebsocketClientStubService"
}

trait Consumer
  extends StreamConsumer
    with StringStreamConsumer
    with DictionaryMapStreamConsumer
    with SetStreamConsumer
    with ListStreamConsumer

object WebSocketClient {

  case class WebsocketConnection(connection: Option[ActorRef] = None)

  case object Connecting extends ActorState

  case object Established extends ActorState

}

class WebSocketClient(id: String, endpoint: String, port: Int)
  extends StatefulActor[WebsocketConnection]
    with Consumer
    with Stash
    with WebsocketClientStubServiceEvt {

  addEvtFields('id -> id)

  startWith(Connecting, WebsocketConnection())

  when(Connecting) {
    case Event(Http.Connected(remoteAddress, localAddress), state) =>
      val upgradePipelineStage = { response: HttpResponse =>
        response match {
          case websocket.HandshakeResponse(st) =>
            st match {
              case wsFailure: websocket.HandshakeFailure => None
              case wsContext: websocket.HandshakeContext => Some(websocket.clientPipelineStage(self, wsContext))
            }
        }
      }
      ConnectionEstablished()
      sender() ! UHttp.UpgradeClient(upgradePipelineStage, upgradeRequest)
      stay()

    case Event(UHttp.Upgraded, state: WebsocketConnection) =>
      ConnectionUpgraded()
      unstashAll()
      transitionTo(Established) using state.copy(connection = Some(sender()))

    case Event(Http.CommandFailed(con: Connect), state) =>
      val msg = s"failed to connect to ${con.remoteAddress}"
      Error('msg -> msg)
      stop(FSM.Failure(msg))

    case Event(_, state) =>
      stash()
      stay()
  }

  when(Established) {
    case Event(ev: Http.ConnectionClosed, state) =>
      ConnectionClosed()
      stop(FSM.Normal)
    case Event(t: BinaryDialectInbound, state: WebsocketConnection) =>
      state.connection.foreach(_ ! BinaryFrame(encode(t)))
      stay()
  }

  onMessage {
    case TextFrame(bs) => // unsupported for now
    case BinaryFrame(bs) => decode(bs) foreach {
      case BinaryDialectPing(pid) =>
        self ! BinaryDialectPong(pid)
        ReceivedPing('pingId -> pid)
      case BinaryDialectSubscriptionClosed(alias) =>
        ReceivedSubscriptionClosed('alias -> alias)
      case BinaryDialectServiceNotAvailable(service) =>
        ReceivedServiceNotAvailable('service -> service)
      case BinaryDialectInvalidRequest(alias) =>
        ReceivedInvalidRequest('alias -> alias)
      case BinaryDialectStreamStateUpdate(alias, state) =>
        ReceivedStreamStateUpdate('alias -> alias, 'state -> state)
        translate(alias, state)
      case BinaryDialectStreamStateTransitionUpdate(alias, trans) =>
        ReceivedStreamStateTransitionUpdate('alias -> alias, 'transition -> trans)
        transition(alias, trans)
      case BinaryDialectSignalAckOk(alias, correlation, payload) =>
        ReceivedSignalAckOk('alias -> alias, 'correlation -> correlation, 'payload -> payload)
      case BinaryDialectSignalAckFailed(alias, correlation, payload) =>
        ReceivedSignalAckFailed('alias -> alias, 'correlation -> correlation, 'payload -> payload)
    }

    case OpenSubscriptionFromStub(subj, key, aggrInt) =>
      self ! BinaryDialectOpenSubscription(aliasFor(subj), key, aggrInt)
    case CloseSubscriptionFromStub(subj) =>
      self ! BinaryDialectCloseSubscription(aliasFor(subj))
    case ResetSubscriptionFromStub(subj) =>
      self ! BinaryDialectResetSubscription(aliasFor(subj))
    case SignalFromStub(subj, payload, expireAt, group, correlation) =>
      self ! BinaryDialectSignal(aliasFor(subj), payload, expireAt, group, correlation)

  }

  private def transition(alias: Int, trans: StreamStateTransition) =
    aliases.find(_._2 == alias).foreach {
      case (s, _) =>
        val st = states.getOrElse(s, None)
        if (trans.applicableTo(st))
          trans.toNewStateFrom(st) match {
            case x@Some(newState) =>
              update(s, newState)
              states += s -> x
            case None => states -= s
          }
    }


  private def translate(alias: Int, state: StreamState) = {
    aliases.find(_._2 == alias).foreach {
      case (s, _) =>
        states += s -> Some(state)
        update(s, state)
    }
  }

  val connect = Http.Connect(endpoint, port, sslEncryption = false)

  val headers = List(
    HttpHeaders.Host(endpoint, port),
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
    HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
    HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate"))

  val upgradeRequest: HttpRequest = HttpRequest(HttpMethods.GET, "/", headers)

  implicit val sys = context.system
  implicit val ec = context.dispatcher

  IO(UHttp) ! connect

  private var counter: Int = 0
  private var aliases: Map[Subject, Int] = Map()
  private var states: Map[Subject, Option[StreamState]] = Map()

  private def aliasFor(subj: Subject) = aliases getOrElse(subj, {
    counter += 1
    aliases += subj -> counter
    self ! BinaryDialectAlias(counter, subj)
    counter
  })


  import rs.core.codec.binary.BinaryCodec.DefaultBinaryCodecImplicits

  def decode(bs: ByteString): List[BinaryDialectOutbound] = {
    @tailrec def dec(l: List[BinaryDialectOutbound], i: ByteIterator): List[BinaryDialectOutbound] = {
      if (!i.hasNext) l else dec(l :+ DefaultBinaryCodecImplicits.clientBinaryCodec.decode(i), i)
    }

    val i = bs.iterator
    val decoded = dec(List.empty, i)
    decoded
  }

  def encode(bdi: BinaryDialectInbound): ByteString = {
    val b = ByteString.newBuilder
    DefaultBinaryCodecImplicits.clientBinaryCodec.encode(bdi, b)
    val encoded = b.result()
    encoded
  }

  onStringRecord {
    case (s, str) => StringUpdate('sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> str)
  }

  onSetRecord {
    case (s, set) => SetUpdate('sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> set.toList.sorted.mkString(","))
  }

  onDictMapRecord {
    case (s, map) => MapUpdate('sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> map.asMap)
  }

  onListRecord {
    case (s, list) => ListUpdate('sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> list.mkString(","))
  }


}


