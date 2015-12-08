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

import rs.core.config.NodeConfig

trait SyseventPublisherContext {
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

class ContextWithFields(val event: Sysevent, f: Seq[FieldAndValue]) extends SyseventPublisherContext {
  var fields = f

  override def +(field: => (Symbol, Any)) = fields = fields :+ field

  override def ++(ff: => Seq[(Symbol, Any)]): Unit = fields = fields ++ ff
}

case object MuteContext extends SyseventPublisherContext {

  override def isMute: Boolean = true

  override def +(field: => (Symbol, Any)): Unit = {}

  override def ++(fields: => Seq[(Symbol, Any)]): Unit = {}
}

trait SyseventPublisher {
  def contextFor(event: Sysevent, values: => Seq[FieldAndValue]): SyseventPublisherContext

  def publish(ctx: SyseventPublisherContext)
}


object SyseventPublisher {
  def apply(cfg: NodeConfig, staticFields: (Symbol, Any)*) = new WithNodeSysevents {
    override val nodeCfg: NodeConfig = cfg

    override val commonFields: Seq[(Symbol, Any)] = super.commonFields ++ staticFields
  }
  def apply(staticFields: (Symbol, Any)*) = new WithSysevents {
    override val commonFields: Seq[(Symbol, Any)] = super.commonFields ++ staticFields
  }
}