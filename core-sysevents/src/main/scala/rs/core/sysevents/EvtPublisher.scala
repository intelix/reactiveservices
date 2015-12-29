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

import com.typesafe.config.{Config, ConfigFactory}
import rs.core.config.{NodeConfig, WithNodeConfig}

trait EvtContext {
  def isMute: Boolean = false

  def +(field: => FieldAndValue): Unit

  def +(f1: => FieldAndValue, f2: => FieldAndValue): Unit = {
    this + f1
    this + f2
  }

  def +(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue): Unit = {
    this + f1
    this + f2
    this + f3
  }

  def +(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue): Unit = {
    this + f1
    this + f2
    this + f3
    this + f4
  }

  def +(f1: => FieldAndValue, f2: => FieldAndValue, f3: => FieldAndValue, f4: => FieldAndValue, f5: => FieldAndValue): Unit = {
    this + f1
    this + f2
    this + f3
    this + f4
    this + f5
  }

  def ++(fields: => Seq[FieldAndValue]): Unit

}

class EvtContextWithFields(val event: Sysevent, f: Seq[FieldAndValue]) extends EvtContext {
  var fields = f

  override def +(field: => (Symbol, Any)) = fields = fields :+ field

  override def ++(ff: => Seq[(Symbol, Any)]): Unit = fields = fields ++ ff
}

case object EvtMuteContext extends EvtContext {

  override def isMute: Boolean = true

  override def +(field: => (Symbol, Any)): Unit = {}

  override def ++(fields: => Seq[(Symbol, Any)]): Unit = {}
}

trait EvtPublisher {
  def contextFor(event: Sysevent, values: => Seq[FieldAndValue]): EvtContext

  def publish(ctx: EvtContext)
}


object EvtPublisher {
  def apply(cfg: Config, staticFields: (Symbol, Any)*): EvtPublisherContext = new WithNodeConfig with EvtPublisherContext {
    override implicit lazy val nodeCfg: NodeConfig = NodeConfig(cfg)
    addEvtFields('nodeid -> nodeId)
    addEvtFields(staticFields: _*)
  }

  def apply(cfg: NodeConfig, staticFields: (Symbol, Any)*): EvtPublisherContext = EvtPublisher(cfg.config, staticFields: _*)

  def apply(staticFields: (Symbol, Any)*): EvtPublisherContext = EvtPublisher(ConfigFactory.empty(), staticFields: _*)
}