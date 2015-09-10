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
package rs.core.services

import scala.language.implicitConversions


case class StreamId(id: String, subId: Option[Any] = None)

object StreamId {
  implicit def toStreamId(id: String): StreamId = StreamId(id, None)
  implicit def toStreamId(id: (String, Any)): StreamId = StreamId(id._1, Some(id._2))
}



