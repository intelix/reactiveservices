package rs.node.testing


import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import akka.io.IO
import akka.util.ByteString
import com.codahale.metrics.Slf4jReporter
import rs.core.Subject
import rs.core.actors.ActorWithComposableBehavior
import rs.core.services.Messages.OpenSubscription
import rs.core.tools.metrics.WithCHMetrics
import spray.can.server.UHttp
import spray.can.websocket.frame.{BinaryFrame, Frame, PongFrame, TextFrame}
import spray.can.{Http, websocket}
import spray.http.{HttpHeaders, HttpMethods, HttpRequest}

import scala.concurrent.duration._
import scala.language.postfixOps


class ClientStub2Actor extends ActorWithComposableBehavior with WithCHMetrics {

  implicit val system = context.system

  val ssl = false


  abstract class WebSocketClient(val id: String, connect: Http.Connect, val upgradeRequest: HttpRequest) extends websocket.WebSocketClientWorker {

    val msgIn = metricRegistry.meter("msgIn." + id)
    val msgSize = metricRegistry.histogram("msgSize." + id)


    IO(UHttp) ! connect

  }

  start(1, 1, 0, "localhost", 9001, "bla")


  var workers: List[ActorRef] = List.empty
  //  onCommand {
  //    case (Subject(_, TopicKey("kill"), _), replyTo, Some(v)) =>
  //      workers foreach context.stop
  //      Some(CommandSucceeded(replyTo, Some("All client simulators stopped")))
  //
  //    case (Subject(_, TopicKey("start"), _), replyTo, Some(v)) =>
  //      for (
  //        j <- Some(Json.parse(v));
  //        count <- j +> 'count;
  //        instruments <- j +> 'instruments;
  //        slowClients <- j +> 'slowClients;
  //        port <- j +> 'port;
  //        host <- j ~> 'host;
  //        sys <- j ~> 'sys
  //      ) yield {
  //        start(count, instruments, slowClients, host, port, sys)
  //        CommandSucceeded(replyTo, Some(s"Started $count normal clients and $slowClients slow clients, each subscribes to $instruments instruments from $sys. Clients will connect to $host:$port"))
  //      }
  //  }

  private def dblFmt(d: Double) = f"$d%1.2f"

  val reporter = Slf4jReporter.forRegistry(metricRegistry)
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .build()

  //  reporter.start(10, TimeUnit.SECONDS)

  case class StartClient(i: Int, instruments: Int, endpoint: String, port: Int, system: String)

  onMessage {
    case StartClient(i, instruments, endpoint, port, sys) =>
      println(s"Starting client $i with $instruments subscriptions ")

      workers = workers :+ startWorker("f" + i, instruments, endpoint, port, sys, slow = false)
  }

  private def start(c: Int, instruments: Int, slowClients: Int, endpoint: String, port: Int, system: String): Unit = {
    println(s"Starting $c websocket clients...")
    for (i <- 1 to c) scheduleOnce(i * 100 millis, StartClient(i, instruments, endpoint, port, system))
    //    for (i <- 1 to slowClients) workers = workers :+ startWorker("s" + i, instruments, endpoint, port, system, slow = true)
  }


  def startWorker(id: String, instruments: Int, endpoint: String, port: Int, system: String, slow: Boolean) = {
    val connect = Http.Connect(endpoint, port, ssl)

    val withCompression = true
    val headers = List(
      HttpHeaders.Host(endpoint, port),
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
      HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ) ++ (if (withCompression) List(HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate")) else List())

    val getCaseCountReq = HttpRequest(HttpMethods.GET, "/bla", headers)


    context.actorOf(Props(
      new WebSocketClient(id, connect, getCaseCountReq) {

        implicit val ec = context.system.dispatcher

        context.system.scheduler.scheduleOnce(5 seconds, self, "subscribe")

        var subscribed = 0
        var authenticated = false

        //        def decodePacket(s: String): String = s match {
        //          case b: String if b.head == WebsocketProtocol.CompressedMessageFlag =>
        //            Try {
        //              LZString.decompressFromUTF16(b.tail) match {
        //                case null => ""
        //                case x => x
        //              }
        //            } recover {
        //              case _ => ""
        //            } getOrElse ""
        //          case b: String if b.head == WebsocketProtocol.UncompressedMessageFlag => b.tail
        //          case _ => ""
        //        }

        //        private def nextSubscriptionCycle() = context.system.scheduler.scheduleOnce(5 seconds, self, "subscribe")

        //        private def authenticate() = {
        //          authenticated = true
        //          //          connection ! TextFrame("fA" + "user_auth" + DefaultPayloadLevel2SegmentSplitChar + "authenticate" + DefaultMessageSegmentSplitChar + "a3")
        //          //          connection ! TextFrame("fC" + "a3" + DefaultMessageSegmentSplitChar + """{"l":"user1","p":"coffee"}""")
        //
        //          nextSubscriptionCycle()
        //        }

        val availablePairs = Seq(
          "AUDCAD", "CHFJPY", "EURNZD", "GBPJPY", "TRYJPY", "USDNOK",
          "AUDCHF", "EURAUD", "EURSEK", "GBPNZD", "USDCAD", "USDSEK",
          "AUDJPY", "EURCAD", "EURTRY", "GBPUSD", "USDCHF", "USDTRY",
          "AUDNZD", "EURCHF", "EURUSD", "NZDCAD", "USDCNH", "USDZAR",
          "AUDUSD", "EURGBP", "GBPAUD", "NZDCHF", "USDHKD", "ZARJPY",
          "CADCHF", "EURJPY", "GBPCAD")

        //        private def subscribe() = {
        //          //          logger.info("!>>>> Next subscription cycle")
        //          for (
        //            i <- 1 to 5 if subscribed < instruments
        //          ) {
        //            subscribed += 1
        //            val pair = availablePairs(subscribed % availablePairs.length)
        //            val volume = (Math.random() * 10000000).toInt
        //
        //            //            logger.info(s"!>>>> $id subscribing to " + s"inst:$pair")
        //
        //            //            connection ! TextFrame("fA" + system + DefaultPayloadLevel2SegmentSplitChar + s"$pair:$volume" + DefaultMessageSegmentSplitChar + "sn" + subscribed)
        //            connection ! TextFrame("fA" + system + DefaultPayloadLevel2SegmentSplitChar + s"inst:$pair" + DefaultMessageSegmentSplitChar + "sn" + subscribed)
        //            connection ! TextFrame("fS" + "sn" + subscribed + DefaultMessageSegmentSplitChar)
        //          }
        //          if (subscribed >= instruments)
        //            logger.info(s"!>>> $id now subscribed to $subscribed, now $now")
        //
        //        }

        //        private def unsubscribe() = {
        //          subscribed = true
        //          connection ! TextFrame("fU" + "a1" + DefaultMessageSegmentSplitChar)
        //          connection ! TextFrame("fU" + "a2" + DefaultMessageSegmentSplitChar)
        //        }

        var cnt = 0

        def businessLogic: Receive = {
          case "subscribe" =>
            //            if (subscribed < instruments) {
            //              subscribe()
            //              nextSubscriptionCycle()
            //            }
            /*
            var b = ByteString.newBuilder
            Codec.encode(OpenSubscription(Subject("pub", "map")), b)
            var result = b.result()
            println(s"!>>> Subscribing!, size: " + result.size)
            connection ! BinaryFrame(result)

            b = ByteString.newBuilder
            Codec.encode(OpenSubscription(Subject("pub", "string")), b)
            result = b.result()
            println(s"!>>> Subscribing!, size: " + result.size)
            connection ! BinaryFrame(result)

            b = ByteString.newBuilder
            Codec.encode(OpenSubscription(Subject("pub", "list")), b)
            result = b.result()
            println(s"!>>> Subscribing!, size: " + result.size)
            connection ! BinaryFrame(result)
            */
          case frame: Frame =>
            onMessage(frame)
          case _: Http.ConnectionClosed =>
            onClose()
            context.stop(self)
        }


        implicit val byteOrder = ByteOrder.BIG_ENDIAN
//        implicit val enc = Codec


        def onMessage(frame: Frame) {
          frame match {
            case _: PongFrame => println(s"!>>> Received PongFrame")
            case BinaryFrame(bs) =>
              val i = bs.iterator
              var c = 0
              while (i.hasNext) {
                c += 1
//                val decoded = WebsocketCodec.DefaultCodecs.Codec.decode(i)
//                println(s"!>>>> Decoded [$c]: " + decoded)
              }
            //            case TextFrame(bs) =>
            //              if (slow) {
            //                Thread.sleep(200)
            //              }
            //              val s = decodePacket(bs.utf8String)
            //              //                            logger.info(s"!>>> $id at $now Received " + s)
            //              val msgs = s.split(DefaultPacketSegmentSplitChar)
            //              if (s.startsWith("P")) {
            //                cnt = cnt + 1
            //                //                if (cnt == 10) unsubscribe()
            //                connection ! frame
            //                if (!authenticated && cnt > 3) {
            //                  authenticate()
            //                }
            //              } else {
            //                msgSize.update(bs.length)
            //                msgIn.mark(msgs.length)
            //              }
            case x: TextFrame => println(s"!>>> UNEXPECTED: " + x.payload.utf8String)

            //connection ! frame
          }
        }

        def onClose() {
          println(s"!>>>> Closed")
        }
      }))
  }


  override def componentId: String = "ClientStubActor"
}
