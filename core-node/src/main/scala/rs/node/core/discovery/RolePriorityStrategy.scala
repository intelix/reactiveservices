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
package rs.node.core.discovery

import rs.core.config.ConfigOps.wrap
import rs.core.config.GlobalConfig

class RolePriorityStrategy extends JoinStrategy {

  def roleWeights(implicit cfg: GlobalConfig) = cfg.asConfig("node.cluster.join.role-weights")

  def roleWeight(role: String)(implicit cfg: GlobalConfig) = roleWeights.asInt(role, 0)

  def sumOfRoleWeights(roles: Set[String])(implicit cfg: GlobalConfig) = roles.map(roleWeight).sum

  override protected def pickFrom(a: ReachableCluster, b: ReachableCluster)(implicit cfg: GlobalConfig): ReachableCluster =
    (sumOfRoleWeights(a.roles), sumOfRoleWeights(b.roles)) match {
      case (aW, bW) if aW > bW => a
      case (aW, bW) if aW < bW => b
      case _ if a.members.size > b.members.size => a
      case _ if a.members.size < b.members.size => b
      case _ if a.age.nonEmpty && b.age.nonEmpty && a.age.get > b.age.get => a
      case _ if a.age.nonEmpty && b.age.nonEmpty && a.age.get < b.age.get => b
      case _ => a
    }

}
