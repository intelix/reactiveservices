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
package rs.core.services.endpoint.akkastreams

import akka.stream.scaladsl.BidiFlow
import akka.util.ByteString
import rs.core.config.{NodeConfig, ServiceConfig}
import rs.core.services.Messages.{ServiceInbound, ServiceOutbound}

trait StageBuilder[I1, O1, I2, O2] {
  def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: NodeConfig): Option[BidiFlow[I1, O1, I2, O2, Unit]]
}

trait BytesStageBuilder extends StageBuilder[ByteString, ByteString, ByteString, ByteString]

trait ProtocolDialectStageBuilder[DI, DO] extends StageBuilder[DI, DI, DO, DO]

trait ServiceDialectStageBuilder extends StageBuilder[ServiceInbound, ServiceInbound, ServiceOutbound, ServiceOutbound]
