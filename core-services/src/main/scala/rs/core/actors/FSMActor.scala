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

import akka.actor.{Actor, ActorRef, FSM, Terminated}
import com.typesafe.scalalogging.StrictLogging
import rs.core.sysevents.WithSysevents
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.core.tools.NowProvider

trait BaseActorSysevents extends ComponentWithBaseSysevents {
  val PostStop = "Lifecycle.PostStop".info
  val PreStart = "Lifecycle.PreStart".info
  val PreRestart = "Lifecycle.PreRestart".info
  val PostRestart = "Lifecycle.PostRestart".info
  val StateTransition = "Lifecycle.StateTransition".info
  val StateChange = "StateChange".info
}


trait ActorState

trait FSMActor[T] extends FSM[ActorState, T] with BaseActor {
  @throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    initialize()
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    initialize()
  }


  def transitionTo(state: ActorState) = {
    if (stateName != state) StateChange('to -> state, 'from -> stateName)
    goto(state)
  }


  private var chainedUnhandled: StateFunction = {
    case Event(Terminated(ref), _) =>
      terminatedFuncChain.foreach(_ (ref))
      stay()
  }

  final def otherwise(f: StateFunction) = {
    chainedUnhandled = f orElse chainedUnhandled
  }

  whenUnhandled {
    case x if chainedUnhandled.isDefinedAt(x) => chainedUnhandled(x)
  }


  final override def onMessage(f: Receive) = otherwise {
    case Event(x, _) if f.isDefinedAt(x) =>
      f(x)
      stay()
  }


}

trait JBaseActor extends BaseActor {

  private var chainedFunc: Receive = {
    case Terminated(ref) => terminatedFuncChain.foreach(_ (ref))
  }

  override final def onMessage(f: Receive): Unit = chainedFunc = f orElse chainedFunc

  override final val receive: Actor.Receive = {
    case x if chainedFunc.isDefinedAt(x) => chainedFunc(x)
    case x => unhandled(x)
  }
}


trait BaseActor
  extends ActorUtils
    with StrictLogging
    with BaseActorSysevents
    with WithSysevents
    with NowProvider
    with WithGlobalConfig {


  protected[actors] var terminatedFuncChain: Seq[ActorRef => Unit] = Seq.empty

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


  def onMessage(f: Receive)


}
