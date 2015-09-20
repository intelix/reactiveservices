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

package rs.core.actors

import akka.actor.{ActorRef, Terminated}
import com.typesafe.scalalogging.StrictLogging
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.core.tools.NowProvider
import rs.core.tools.metrics.MetricGroups.ActorMetricGroup
import rs.core.tools.metrics.Metrics

trait BaseActorSysevents extends ComponentWithBaseSysevents {
  val PostStop = "Lifecycle.PostStop".info
  val PreStart = "Lifecycle.PreStart".info
  val PreRestart = "Lifecycle.PreRestart".info
  val PostRestart = "Lifecycle.PostRestart".info
}


trait ActorWithComposableBehavior extends ActorUtils with WithInstrumentationHooks with StrictLogging with BaseActorSysevents with WithSyseventPublisher with NowProvider {

  private lazy val MessageProcessingTimer = timerSensor(ActorMetricGroup, Metrics.ProcessingTime)
  private lazy val ArrivalRateMeter = meterSensor(ActorMetricGroup, Metrics.ArrivalRate)
  private lazy val FailureRateMeter = meterSensor(ActorMetricGroup, Metrics.FailureRate)

  private val NoHandler: Any => Unit = _ => {}

  private var terminatedFuncChain: Seq[ActorRef => Unit] = Seq.empty

  def onActorTerminated(f: ActorRef => Unit) = terminatedFuncChain = terminatedFuncChain :+ f


  private val pathAsString = self.path.toStringWithoutAddress
  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('path -> pathAsString)

  @throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    PreRestart('reason -> reason.getMessage, 'msg -> message, 'path -> self.path.toStringWithoutAddress)
    super.preRestart(reason, message)
  }


  @throws[Exception](classOf[Exception])
  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    PostRestart('reason -> reason.getMessage, 'path -> self.path.toStringWithoutAddress)
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    PreStart('path -> self.path.toStringWithoutAddress)
    super.preStart()
  }

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    super.postStop()
    PostStop('path -> self.path.toStringWithoutAddress)
  }

  private var commonBehavior: Receive = {
    case Terminated(ref) => terminatedFuncChain.foreach(_ (ref))
  }

  def beforeMessage() = {}

  def afterMessage() = {}

  final def onMessage(f: Receive) = commonBehavior = f orElse commonBehavior

  final override def receive: Receive = {
    case x =>
      val startStamp = System.nanoTime()

      beforeMessage()

      processMessage(x)

      afterMessage()

  }


  protected def processMessage(x: Any) =
    try {
      commonBehavior applyOrElse(x, NoHandler)
    } catch {
      case x: Throwable =>
        Error('msg -> x.getMessage, 'err -> x)
        FailureRateMeter.update(1)
        throw x;
    }

}
