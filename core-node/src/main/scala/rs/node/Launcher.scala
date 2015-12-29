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
package rs.node

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import rs.core.config.NodeConfig
import rs.node.core.ServiceClusterGuardianActor

object Launcher extends App {

  private val configName: String = java.lang.System.getProperty("config", "node.conf")
  private val config = if (configName == null) ConfigFactory.empty() else ConfigFactory.load(configName)
  implicit val nodeCfg: NodeConfig = NodeConfig(config)

  private val localSystemConfigName: String = java.lang.System.getProperty("local-config", "node-local.conf")
  private val localSystemName: String = java.lang.System.getProperty("local-system", "local")
  implicit val system = ActorSystem(localSystemName, ConfigFactory.load(localSystemConfigName))

  ServiceClusterGuardianActor.start(nodeCfg)

}
