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
package rs.core.sysevents

import rs.core.config.ConfigOps.wrap
import rs.core.config.{WithConfig, NodeConfig}
import rs.core.sysevents.log.LoggerSyseventPublisher

trait WithSysevents {

  implicit val evtPublisher = WithSysevents.publisher
  implicit val evtPublisherContext = this

  def commonFields: Seq[(Symbol, Any)] = Seq()
}

trait WithNodeSysevents extends WithSysevents {

  override implicit val evtPublisherContext = this

  val nodeCfg: NodeConfig

  lazy val nodeId = nodeCfg.asString("node.id", "n/a")

  override def commonFields: Seq[(Symbol, Any)] = Seq('nodeid -> nodeId)
}


object WithSysevents extends WithSyseventsConfig {
  val publisher = syseventsConfig.asClass("sysevents.publisher-provider", classOf[LoggerSyseventPublisher]).newInstance().asInstanceOf[SyseventPublisher]
}
