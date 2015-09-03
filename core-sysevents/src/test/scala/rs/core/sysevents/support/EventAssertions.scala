package rs.core.sysevents.support

import com.typesafe.scalalogging.StrictLogging
import core.sysevents.FieldAndValue
import rs.core.sysevents.SyseventOps.symbolToSyseventOps
import rs.core.sysevents._
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import org.slf4j.LoggerFactory

trait EventAssertions extends Matchers with EventMatchers with BeforeAndAfterEach with BeforeAndAfterAll with StrictLogging {
  self: org.scalatest.Suite =>

  SyseventPublisherRef.ref = new TestSyseventPublisher()
  SyseventSystemRef.ref = SEvtSystem("test")

  def clearEvents() =
    SyseventPublisherRef.ref.asInstanceOf[TestSyseventPublisher].clear()

  def clearComponentEvents(componentId: String) =
    SyseventPublisherRef.ref.asInstanceOf[TestSyseventPublisher].clearComponentEvents(componentId)

  override protected def beforeAll(): Unit = {
    logger.warn("**** > Starting " + this.getClass)
    super.beforeAll()
  }

  override protected def afterAll() {
    super.afterAll()
    logger.warn("**** > Finished " + this.getClass)
  }


  override protected def afterEach(): Unit = {
    clearEvents()
    super.afterEach()
  }

  def events = SyseventPublisherRef.ref.asInstanceOf[TestSyseventPublisher].events

  override protected def beforeEach(): Unit = {
    super.beforeEach()
  }

  implicit val patienceConfig =
    PatienceConfig(timeout = scaled(Span(5, Seconds)), interval = scaled(Span(15, Millis)))


  def collectAndPrintEvents() = {
    Thread.sleep(10000)
    printRaisedEvents()
  }

  def printRaisedEvents() = {
    val log = LoggerFactory.getLogger("history")
    log.error("*" * 60 + " RAISED EVENTS: " + "*" * 60)
    SyseventPublisherRef.ref.asInstanceOf[TestSyseventPublisher].withOrderedEvents { events =>
      events.foreach { next =>
        LoggerSyseventPublisherWithDateHelper.log(next.timestamp, next.event, SyseventSystemRef.ref, next.values, s => log.error(s))
      }
    }
    log.error("*" * 120 + "\n\n\n\n")
  }

  def report(x: Throwable) = {
    val log = LoggerFactory.getLogger("history")
    val log2 = LoggerFactory.getLogger("test")
    log2.error("Test failed", x)
    log2.error("*" * 60 + " RAISED EVENTS: " + "*" * 60)
    log2.error("Raised sysevents:")
    SyseventPublisherRef.ref.asInstanceOf[TestSyseventPublisher].withOrderedEvents { events =>
      events.foreach { next =>
        LoggerSyseventPublisherWithDateHelper.log(next.timestamp, next.event, SyseventSystemRef.ref, next.values, s => log.error(s))
      }
    }
    log2.error("*" * 120 + "\n\n\n\n")
  }

  def waitWithTimeout(millis: Long)(f: => Unit) = {
    val allowedAttempts = millis / 15
    var attempt = 0
    var success = false
    while (!success && attempt < allowedAttempts) {
      try {
        f
        success = true
      } catch {
        case x: Throwable =>
          Thread.sleep(15)
          attempt = attempt + 1
      }
    }
    try {
      f
    } catch {
      case x: Throwable =>
        SyseventPublisherRef.ref.publish(SyseventSystemRef.ref, ErrorSysevent("ExpectationFailed", "Test"), Seq())
        report(x)
        throw x
    }
  }

  def locateAllEvents(event: Sysevent) = {
    expectOneOrMoreEvents(event)
    events.get(event)
  }

  def locateLastEvent(event: Sysevent) = locateAllEvents(event).map(_.head)

  def locateFirstEvent(event: Sysevent) = locateAllEvents(event).map(_.head)

  def locateFirstEventFieldValue(event: Sysevent, field: String) = {
    val maybeValue = for (
      all <- locateAllEvents(event);
      first = all.head;
      (f, v) <- first.find { case (f, v) => f.name == field}
    ) yield v
    maybeValue.get
  }

  def locateLastEventFieldValue(event: Sysevent, field: String) = {
    val maybeValue = for (
      all <- locateAllEvents(event);
      first = all.last;
      (f, v) <- first.find { case (f, v) => f.name == field}
    ) yield v
    maybeValue.get
  }

  def waitAndCheck(f: => Unit) = duringPeriodInMillis(500)(f)

  def duringPeriodInMillis(millis: Long)(f: => Unit) = {
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < millis) {
      f
      Thread.sleep(50)
    }

  }

  def expectNoEvents(event: Sysevent, values: FieldAndValue*): Unit =
    try {
      events.get(event).foreach(_ shouldNot haveAllValues(values))
    } catch {
      case x: Throwable =>
        report(x)
        throw x
    }

  def expectOneOrMoreEvents(event: Sysevent, values: FieldAndValue*): Unit = expectSomeEventsWithTimeout(5000, event, values: _*)

  def expectExactlyNEvents(count: Int, event: Sysevent, values: FieldAndValue*): Unit = expectRangeOfEventsWithTimeout(5000, count to count, event, values: _*)

  def expectNtoMEvents(count: Range, event: Sysevent, values: FieldAndValue*): Unit = expectRangeOfEventsWithTimeout(5000, count, event, values: _*)

  def expectSomeEventsWithTimeout(timeout: Int, event: Sysevent, values: FieldAndValue*): Unit = {
    waitWithTimeout(timeout) {
      val e = Map() ++ events
      e should contain key event
      e.get(event).get should haveAllValues(values)
    }
  }

  def expectSomeEventsWithTimeout(timeout: Int, c: Int, event: Sysevent, values: FieldAndValue*): Unit =
    expectRangeOfEventsWithTimeout(timeout, c to c, event, values: _*)

  def expectRangeOfEventsWithTimeout(timeout: Int, count: Range, event: Sysevent, values: FieldAndValue*): Unit = {
    waitWithTimeout(timeout) {
      val e = Map() ++ events
      e should contain key event
      e.get(event).get should haveAllValues(count, values)
    }
  }

}
