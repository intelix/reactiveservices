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
package au.com.intelix.rs.core.codec.binary

import akka.stream.BidiShape
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL}
import akka.util.ByteString
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.config.RootConfig
import au.com.intelix.rs.core.config.ServiceConfig
import au.com.intelix.rs.core.services.endpoint.akkastreams.BytesStageBuilder

import scala.concurrent.duration._
import scala.language.postfixOps

class ByteStringAggregatorStage extends BytesStageBuilder {

  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: RootConfig) =
    if (serviceCfg.asBoolean("aggregator.enabled", defaultValue = true))
      Some(BidiFlow.fromGraph(GraphDSL.create() { b =>

        val maxMessages = serviceCfg.asInt("aggregator.max-messages", 100)
        val within = serviceCfg.asFiniteDuration("aggregator.time-window", 100 millis)

        val in = b.add(Flow[ByteString])
        val out = b.add(Flow[ByteString].groupedWithin(maxMessages, within).map {
          case Nil => ByteString.empty
          case bs :: Nil => bs.compact
          case s => s.foldLeft(ByteString.empty)(_ ++ _).compact
        })
        BidiShape.fromFlows(in, out)
      }))
    else None
}
