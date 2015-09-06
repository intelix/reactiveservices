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

  def onMessage[T](cl: Class[T], callback: MessageCallback[T]): Unit = {
    super.onMessage {
      case x if cl.isAssignableFrom(x.getClass) => callback.handle(x.asInstanceOf[T])
    }
  }

  def onStreamActive(streamRef: String, callback: StreamStateCallback): Unit = {
    onStreamActive {
      case x: StreamId if x.id == streamRef => callback.handle(x.id)
    }
  }

  def onSignalForTopic(topic: String, callback: SignalCallback): Unit = {
    onSignal {
      case (s, p) if s.topic.id == topic || s.topic.id.startsWith(topic) =>
        callback.handle(s, p)
    }
  }

  def topicToStreamRef(topic: String, streamRef: String): Unit = {
    onSubjectSubscription {
      case s if s.topic.id == topic => Some(streamRef)
    }
  }

  def topicToStreamRef(topic: String, mapper: SubjectMapper): Unit = {
    onSubjectSubscription {
      case s if s.topic.id == topic => Some(mapper.map(s))
    }
  }

  def scheduleOnceToSelf(millis: Long, msg: Any) = scheduleOnce(millis millis, msg, self, self)


}
