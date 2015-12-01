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
package rs.core.codec.binary

import akka.stream.BidiShape
import akka.stream.scaladsl.{BidiFlow, Flow}
import akka.util.ByteString
import rs.core.config.ConfigOps.wrap
import rs.core.config.{GlobalConfig, ServiceConfig}
import rs.core.services.endpoint.akkastreams.BytesStageBuilder
import rs.core.sysevents.WithSysevents

import scala.concurrent.duration._
import scala.language.postfixOps

class ByteStringAggregator extends BytesStageBuilder {

  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, globalConfig: GlobalConfig, pub: WithSysevents) =
    if (serviceCfg.asBoolean("aggregator.enabled", defaultValue = true))
      Some(BidiFlow() { b =>

        val maxMessages = serviceCfg.asInt("aggregator.max-messages", 100)
        val within = serviceCfg.asFiniteDuration("aggregator.time-window", 100 millis)

        val in = b.add(Flow[ByteString])
        val out = b.add(Flow[ByteString].groupedWithin(maxMessages, within).map {
          case Nil => ByteString.empty
          case bs :: Nil => bs
          case s => s.foldLeft(ByteString.empty)(_ ++ _)
        })
        BidiShape(in, out)
      })
    else None
}
