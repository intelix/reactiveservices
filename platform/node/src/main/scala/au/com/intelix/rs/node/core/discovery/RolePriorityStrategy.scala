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
package au.com.intelix.rs.node.core.discovery

import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.config.RootConfig

class RolePriorityStrategy extends JoinStrategy {

  def roleWeights(implicit nodeCfg: RootConfig) = nodeCfg.asConfig("node.cluster.join.role-weights")

  def roleWeight(role: String)(implicit nodeCfg: RootConfig) = roleWeights.asInt(role, 0)

  def sumOfRoleWeights(roles: Set[String])(implicit nodeCfg: RootConfig) = roles.map(roleWeight).sum

  override protected def pickFrom(a: ReachableCluster, b: ReachableCluster)(implicit nodeCfg: RootConfig): ReachableCluster =
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
