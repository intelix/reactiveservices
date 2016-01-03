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
package rs.core.services.endpoint

import rs.core.stream.StreamState
import rs.core.{ServiceKey, Subject}

import scala.language.postfixOps


trait StreamConsumer {

  type StreamUpdateHandler = PartialFunction[(Subject, StreamState), Unit]
  type ServiceKeyEventHandler = PartialFunction[ServiceKey, Unit]

  val HighestPriority = Some("A")

  private var missingServices: Set[ServiceKey] = Set.empty

  private var streamUpdateHandlerFunc: StreamUpdateHandler = {
    case _ =>
  }
  private var serviceNotAvailableHandlerFunc: ServiceKeyEventHandler = {
    case _ =>
  }
  private var serviceAvailableHandlerFunc: ServiceKeyEventHandler = {
    case _ =>
  }

  def onStreamUpdate(handler: StreamUpdateHandler) = streamUpdateHandlerFunc = handler orElse streamUpdateHandlerFunc

  def onServiceNotAvailable(handler: ServiceKeyEventHandler) = serviceNotAvailableHandlerFunc = handler orElse serviceNotAvailableHandlerFunc

  def onServiceAvailable(handler: ServiceKeyEventHandler) = serviceAvailableHandlerFunc = handler orElse serviceAvailableHandlerFunc


  protected[endpoint] def update(subj: Subject, tran: StreamState) = {
    if (missingServices.contains(subj.service)) {
      missingServices -= subj.service
      serviceAvailableHandlerFunc(subj.service)
    }
    streamUpdateHandlerFunc((subj, tran))
  }

  protected[endpoint] def processServiceNotAvailable(service: ServiceKey): Unit =
    if (!missingServices.contains(service)) {
      missingServices += service
      serviceNotAvailableHandlerFunc(service)
    }


}
