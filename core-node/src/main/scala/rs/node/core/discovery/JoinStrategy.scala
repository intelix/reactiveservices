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

import rs.core.config.NodeConfig


trait JoinStrategy {
  def selectClusterToJoin(our: Option[ReachableCluster], other: Set[ReachableCluster])(implicit nodeCfg: NodeConfig): Option[ReachableCluster] =
    other.foldLeft[Option[ReachableCluster]](our) {
        case (None, next) => Some(next)
        case (Some(pick), next) => Some(pickFrom(pick, next))
      } match {
      case Some(pick) if isOurCluster(our, pick) => None
      case x => x
    }

  protected def isOurCluster(our: Option[ReachableCluster], other: ReachableCluster) = other.members.exists { m => our.exists(_.members.contains(m)) }

  protected def pickFrom(a: ReachableCluster, b: ReachableCluster)(implicit nodeCfg: NodeConfig): ReachableCluster
}
