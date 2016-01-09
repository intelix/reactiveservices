package rs.core.evt.disruptor

import java.util.concurrent.Executors

import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.{EventFactory, EventHandler}
import com.typesafe.config.Config
import rs.core.config.ConfigOps.wrap
import rs.core.evt.slf4j.Slf4jPublisher
import rs.core.evt.{Evt, EvtPublisher, EvtSource}

trait DetachedEvent {
  def source: EvtSource

  def evt: Evt

  def fields: Seq[(String, Any)]

}

class DisruptorPublisher(cfg: Config) extends EvtPublisher {

  class Event extends DetachedEvent {
    var source: EvtSource = null
    var evt: Evt = null
    var fields: Seq[(String, Any)] = null
  }
  class CleaningWrapper(h: EventHandler[DetachedEvent]) extends EventHandler[Event] {
    override def onEvent(t: Event, l: Long, b: Boolean): Unit = {
      try {
        h.onEvent(t,l,b)
      } finally {
        t.evt = null
        t.fields = null
        t.source = null
      }
    }
  }
  private class PublisherWrapper(p: EvtPublisher) extends EventHandler[DetachedEvent] {
    override def onEvent(event: DetachedEvent, sequence: Long, endOfBatch: Boolean): Unit = p.raise(event.source, event.evt, event.fields)
  }
  val ringSize = cfg.asInt("evt.disruptor.ring-size", 1024 * 16)
  val handler = cfg.asConfigurableInstance[EvtPublisher]("evt.disruptor.delegate-to-publisher", classOf[Slf4jPublisher])
  val exec = Executors.newCachedThreadPool()
  val factory = new EventFactory[Event] {
    override def newInstance(): Event = new Event
  }

  val disruptor = new Disruptor[Event](factory, ringSize, exec)

  disruptor.handleEventsWith(new CleaningWrapper(new PublisherWrapper(handler)))
  val rb = disruptor.getRingBuffer

  disruptor.start()

  override def raise(s: EvtSource, e: Evt, fields: Seq[(String, Any)]): Unit = {
    val id = rb.next()
    try {
      val entry = rb.get(id)
      entry.source = s
      entry.evt = e
      entry.fields = fields
    } finally {
      rb.publish(id)
    }
  }

  override def canPublish(s: EvtSource, e: Evt): Boolean = handler.canPublish(s, e)
}
