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

import akka.actor.{Actor, ActorRef}
import au.com.intelix.essentials.time.NowProvider
import au.com.intelix.essentials.uuid.UUIDTools
import au.com.intelix.rs.core.config.{WithActorSystemConfig, WithNodeConfig}

import scala.concurrent.duration.FiniteDuration

trait ActorUtils extends Actor with WithActorSystemConfig with NowProvider with WithNodeConfig {

  lazy val uuid = generateUUID

  def generateUUID: String = UUIDTools.generateUUID

  def scheduleOnce(in: FiniteDuration, msg: Any, to: ActorRef = self, from: ActorRef = self) = context.system.scheduler.scheduleOnce(in, to, msg)(context.dispatcher, from)

}
