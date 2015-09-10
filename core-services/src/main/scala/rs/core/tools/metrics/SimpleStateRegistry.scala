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
package rs.core.tools.metrics

import scala.collection.concurrent.TrieMap

object SimpleStateRegistry {

  val m: TrieMap[String, String] = TrieMap()

  def getPublisherFor(name: String) = new SimpleStatePublisher(name, s => m.put(name, s))

  def remove(id: String) = m -= id

}

class SimpleStatePublisher(private val name: String, private val publisher: String => Unit) extends StatePublisher {
  override def update(state: String): Unit = publisher(state)
}