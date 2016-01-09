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
package rs.core.sysevents

import rs.core.config.ConfigOps.wrap
import rs.core.config.WithNodeConfig
import rs.core.sysevents.log.LoggerEvtPublisher

trait EvtPublisherContext {
  implicit val evtPublisherContext = this

  final val evtPublisher: EvtPublisher = EvtPublisherContext.publisher

  private[sysevents] var constantFields: Seq[(Symbol, Any)] = Seq()

  def addEvtFields(fields: (Symbol, Any)*) = constantFields = constantFields ++ fields
}

private object EvtPublisherContext extends WithSyseventsConfig {
  val publisher = syseventsConfig.asConfigurableInstance[EvtPublisher]("sysevents.publisher-provider", classOf[LoggerEvtPublisher])
}
