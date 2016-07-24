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

package au.com.intelix.rs.core.actors

import akka.actor.Terminated

trait StatelessActor extends BaseActor {

  private var func: Receive = {
    case Terminated(ref) => terminatedFuncChain.foreach(_ (ref))
  }

  override def onMessage(f: Receive): Unit = func = f orElse func

  override def receive: Receive = {
    case m if func.isDefinedAt(m) => func(m)
    case m =>
      unhandled(m)
  }
}