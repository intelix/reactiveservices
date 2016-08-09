package au.com.intelix.evt.disruptor

import java.util.concurrent.{ThreadFactory, TimeUnit}

import au.com.intelix.evt._
import au.com.intelix.evt.slf4j.Slf4jPublisher
import au.com.intelix.config.ConfigOps.wrap
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.{EventFactory, EventHandler}
import com.typesafe.config.Config

trait DetachedEvent {
  def source: EvtSource

  def evt: String
  def lvl: EvtLevel

  def fields: Seq[EvtFieldValue]

}

class DisruptorPublisher(cfg: Config) extends EvtPublisher with EvtMutingSupport {

  override val eventsConfig: Config = cfg.getConfig("evt.disruptor")

  class Event extends DetachedEvent {
    var source: EvtSource = null
    var evt: String = null
    var lvl: EvtLevel = null
    var fields: Seq[EvtFieldValue] = null
  }

  class CleaningWrapper(h: EventHandler[DetachedEvent]) extends EventHandler[Event] {
    override def onEvent(t: Event, l: Long, b: Boolean): Unit = {
      try {
        h.onEvent(t, l, b)
      } finally {
        t.evt = null
        t.lvl = null
        t.fields = null
        t.source = null
      }
    }
  }

  private class PublisherWrapper(p: EvtPublisher) extends EventHandler[DetachedEvent] {
    override def onEvent(event: DetachedEvent, sequence: Long, endOfBatch: Boolean): Unit = p.evt(event.source, event.evt, event.lvl, event.fields)
  }

  val ringSize = cfg.asInt("evt.disruptor.ring-size", 1024 * 16)
  val handler = cfg.asConfigurableInstance[EvtPublisher]("evt.disruptor.delegate-to-publisher", classOf[Slf4jPublisher])
  val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = new Thread(r, "disruptorConsumer") {
      setDaemon(true)
    }
  }
  val factory = new EventFactory[Event] {
    override def newInstance(): Event = new Event
  }

  val disruptor = new Disruptor[Event](factory, ringSize, threadFactory)

  disruptor.handleEventsWith(new CleaningWrapper(new PublisherWrapper(handler)))
  val rb = disruptor.getRingBuffer

  disruptor.start()

  sys.addShutdownHook {
    disruptor.shutdown(10, TimeUnit.SECONDS)
  }

  override def evt(s: EvtSource, e: String, lvl: EvtLevel, fields: Seq[EvtFieldValue]): Unit = {
    val id = rb.next()
    try {
      val entry = rb.get(id)
      entry.source = s
      entry.evt = e
      entry.lvl = lvl
      entry.fields = fields
    } finally {
      rb.publish(id)
    }
  }

}
