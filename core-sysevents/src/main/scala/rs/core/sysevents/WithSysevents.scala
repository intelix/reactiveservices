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
import rs.core.config.WithExternalConfig
import rs.core.sysevents.log.LoggerSyseventPublisher

trait WithSysevents extends WithExternalConfig {

  implicit val evtPublisher = WithSysevents.ref
  implicit val evtPublisherContext = this

  def commonFields: Seq[FieldAndValue] = Seq()
}

object WithSysevents extends WithExternalConfig {
  private lazy val ref = globalConfig.asClass("sysevents.publisher-provider", classOf[LoggerSyseventPublisher]).newInstance().asInstanceOf[SyseventPublisher]

}
