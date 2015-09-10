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
package rs.service.repl

import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import rs.core.services.Messages._
import rs.core.{ServiceKey, Subject}

import scala.language.postfixOps

object SimpleREPLProtocol {

  case class Feedback(text: String)

  trait REPLContext {

    private var aliases: Map[String, Subject] = Map.empty

    def addAlias(name: String, value: Subject) = synchronized {
      println(s"!>>> added $name")
      aliases += name -> value
    }

    def locate(name: String): Option[Subject] = synchronized {
      println(s"!>>> locating $name = ${aliases.get(name)}")
      aliases get name
    }
  }


  private def parseSubject(value: String): Option[Subject] =
    if (value.contains('|')) {
      val values = value.split('|')
      val service = values(0)
      val topic = values(1)
      Some(Subject(service, topic))
    } else None


  def fromBytes(bytes: ByteString)(implicit ctx: REPLContext): List[Any] = {
    val utf8 = bytes.utf8String.trim

    println(s"!>>> Received: $utf8")

    if (utf8.length > 0) {
      val tail = utf8.tail
      utf8.head match {
        case 'a' =>
          val values = tail.split('=')
          val name = values(0)
          val value = values(1)
          parseSubject(value) foreach (ctx.addAlias(name, _))
          println(s"!>>> Added alias $name=$value")

          List(Feedback(s"Added alias $name=$value"))
        case 's' =>
          val params = tail.split(',').map(_.trim).toSeq
          println(s"!>>> After split: $params")

          if (params.nonEmpty && params.head.nonEmpty) ctx.locate(params.head) match {
            case Some(subj) =>
              println(s"!>>> Subject: $subj")
              val prio = if (params.length > 1) Some(params(1)) else None
              val aggr = if (params.length > 2) params(2).toInt else 0
              List(OpenSubscription(subj, prio, aggr), Feedback(s"Subscribed to $subj, priority=$prio, aggregation=$aggr ms"))
            case None =>
              List(Feedback(s"Unrecognised alias ${params.head}"))
          } else
            List(Feedback(s"Invalid subscribe request"))
        case 'u' =>
          ctx.locate(tail) match {
            case Some(subj) =>
              List(CloseSubscription(subj), Feedback(s"Unsubscribed from $subj"))
            case None =>
              List(Feedback(s"Unrecognised alias $tail"))
          }
        case x => List(Feedback(s"Unrecognised command $x"))
      }
    } else List.empty
  }

  def toBytes(m: Any)(implicit ctx: REPLContext): ByteString =
    m match {
      case Feedback(text) => ByteString(text + "\n")
      case ServiceNotAvailable(ServiceKey(key)) => ByteString(s"Service not available: $key\n")
      case StreamStateUpdate(subj, state) => ByteString(s"$subj -> $state\n")
      case x => ByteString(s"Unrecognised response: $x\n")
    }


  def apply(): BidiFlow[ByteString, Any, Any, ByteString, Unit] = {
    class FeedbackRouter[T] extends FlexiRoute[T, UniformFanOutShape[T, T]](new UniformFanOutShape(2), Attributes.name("FeedbackRouter")) {

      import FlexiRoute._

      override def createRouteLogic(s: PortT): RouteLogic[T] = new RouteLogic[T] {
        override def initialState: State[Unit] =
          State(DemandFromAll(s.out(0), s.out(1))) {
            (ctx, _, el) =>
              el match {
                case x: Feedback => ctx.emit(s.out(1))(el)
                case x => ctx.emit(s.out(0))(el)
              }
              SameState
          }


        override def postStop(): Unit = {
          println(s"!>>> FeedbackRouter stopped")
          super.postStop()
        }

        override def initialCompletionHandling: CompletionHandling = CompletionHandling(
          onUpstreamFailure = (ctx, cause) => ctx.finish(),
          onUpstreamFinish = ctx => ctx.finish(),
          onDownstreamFinish = (ctx, _) => {
            ctx.finish();
            SameState
          }
        )
      }
    }


    implicit val ctx = new REPLContext() {}

    val shape = FlowGraph.partial() { implicit fb =>
      import FlowGraph.Implicits._

      val router = fb.add(new FeedbackRouter[Any])
      val translateFromBytes = fb.add(Flow[ByteString].mapConcat(fromBytes))
      val translateToBytes = fb.add(Flow[Any].map(toBytes))
      val merge = fb.add(MergePreferred[Any](1))
      println(s"!>>> constructed A")

      translateFromBytes ~> router.in
      router.out(1) ~> merge.preferred
      merge ~> translateToBytes

      println(s"!>>> constructed B")

      BidiShape(FlowShape(translateFromBytes.inlet, router.out(0)), FlowShape(merge.in(0), translateToBytes.outlet))
    }

    val zz = BidiFlow.wrap(shape)
    zz
  }


}
