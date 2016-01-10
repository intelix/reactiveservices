package rs.core.evt.disruptor

import java.util.concurrent.{TimeUnit, Executors, ThreadFactory}

import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.{EventFactory, EventHandler}
import com.typesafe.config.Config
import rs.core.config.ConfigOps.wrap
import rs.core.evt.slf4j.Slf4jPublisher
import rs.core.evt._

import scala.sys

trait DetachedEvent {
  def source: EvtSource

  def evt: Evt

  def fields: Seq[EvtFieldValue]

}

class DisruptorPublisher(cfg: Config) extends EvtPublisher with EvtMutingSupport {

  override val eventsConfig: Config = cfg.getConfig("evt.disruptor")

  class Event extends DetachedEvent {
    var source: EvtSource = null
    var evt: Evt = null
    var fields: Seq[EvtFieldValue] = null
  }

  class CleaningWrapper(h: EventHandler[DetachedEvent]) extends EventHandler[Event] {
    override def onEvent(t: Event, l: Long, b: Boolean): Unit = {
      try {
        h.onEvent(t, l, b)
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
  val exec = Executors.newCachedThreadPool(new ThreadFactory {
    override def newThread(r: Runnable): Thread = new Thread(r, "disruptorConsumer") {
      setDaemon(true)
    }
  })
  val factory = new EventFactory[Event] {
    override def newInstance(): Event = new Event
  }

  val disruptor = new Disruptor[Event](factory, ringSize, exec)

  disruptor.handleEventsWith(new CleaningWrapper(new PublisherWrapper(handler)))
  val rb = disruptor.getRingBuffer

  disruptor.start()

  sys.addShutdownHook {
    disruptor.shutdown(10, TimeUnit.SECONDS)
  }

  override def raise(s: EvtSource, e: Evt, fields: Seq[EvtFieldValue]): Unit = {
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

}
