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
package rs.core.javaapi

import rs.core.Subject
import rs.core.services.{StreamId, ServiceCell}
import rs.core.stream._

import scala.concurrent.duration._
import scala.language.postfixOps

abstract class JServiceCell(id: String) extends ServiceCell(id) with JStringStreamPublisher with JListStreamPublisher with JSetStreamPublisher with JDictionaryMapStreamPublisher {

  trait MessageCallback[T] {
    def handle(v: T)
  }

  trait StreamStateCallback {
    def handle(streamRef: String)
  }

  trait SubjectMapper {
    def map(subject: Subject): String
  }

  abstract class SignalCallback {
    def success(): Option[SignalResponse] = Some(SignalOk())

    def success(payload: Any): Option[SignalResponse] = Some(SignalOk(Some(payload)))

    def failure(): Option[SignalResponse] = Some(SignalFailed())

    def failure(payload: Any): Option[SignalResponse] = Some(SignalFailed(Some(payload)))

    def withoutResponse(): Option[SignalResponse] = None

    def handle(subj: Subject, payload: Any): Option[SignalResponse]
  }


  def serviceInitialization(): Unit

  @throws[Exception](classOf[Exception]) override
  final def preStart(): Unit = {
    super.preStart()
    serviceInitialization()
  }

  final def onMessage[T](cl: Class[T], callback: MessageCallback[T]): Unit = {
    super.onMessage {
      case x if cl.isAssignableFrom(x.getClass) => callback.handle(x.asInstanceOf[T])
    }
  }

  final def onStreamActive(streamRef: String, callback: StreamStateCallback): Unit = {
    onStreamActive {
      case x: StreamId if x.id == streamRef => callback.handle(x.id)
    }
  }

  final def onSignalForTopic(topic: String, callback: SignalCallback): Unit = {
    onSignal {
      case (s, p) if s.topic.id == topic || s.topic.id.startsWith(topic) =>
        callback.handle(s, p)
    }
  }

  final def onTopicSubscription(topic: String, streamRef: String): Unit = {
    onSubjectSubscription {
      case s if s.topic.id == topic => Some(streamRef)
    }
  }

  final def onTopicSubscription(topic: String, mapper: SubjectMapper): Unit = {
    onSubjectSubscription {
      case s if s.topic.id == topic => Some(mapper.map(s))
    }
  }

  final def scheduleOnceToSelf(millis: Long, msg: Any) = scheduleOnce(millis millis, msg, self, self)


}
