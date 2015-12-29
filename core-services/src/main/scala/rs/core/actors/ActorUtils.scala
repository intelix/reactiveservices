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

package rs.core.actors

import akka.actor.{Actor, ActorRef}
import rs.core.config.{WithActorSystemConfig, WithSomeConfig, WithNodeConfig, NodeConfig}
import rs.core.tools.{NowProvider, UUIDTools}

import scala.concurrent.duration.FiniteDuration

trait ActorUtils extends Actor with WithActorSystemConfig with NowProvider with WithNodeConfig {

  lazy val uuid = randomUUID

  def randomUUID: String = UUIDTools.generateShortUUID

  def scheduleOnce(in: FiniteDuration, msg: Any, to: ActorRef = self, from: ActorRef = self) = context.system.scheduler.scheduleOnce(in, to, msg)(context.dispatcher, from)

}
