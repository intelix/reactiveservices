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
package rs.core.sysevents.support

import com.typesafe.scalalogging.StrictLogging
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import org.slf4j.LoggerFactory
import rs.core.sysevents._
import rs.core.sysevents.log.StandardLogMessageFormatterWithDate

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.{implicitConversions, postfixOps}

trait EventAssertions extends Matchers with WithSyseventsCollector with WithSysevents with EventMatchers with BeforeAndAfterEach with BeforeAndAfterAll with StrictLogging with StandardLogMessageFormatterWithDate {
  self: org.scalatest.Suite =>

  def clearEvents() = evtPublisher.asInstanceOf[TestSyseventPublisher].clear()

  def clearComponentEvents(componentId: String) = evtPublisher.asInstanceOf[TestSyseventPublisher].clearComponentEvents(componentId)

  def events = evtPublisher.asInstanceOf[TestSyseventPublisher].events

  override protected def beforeAll(): Unit = {
    logger.warn("**** > Starting " + this.getClass)
    super.beforeAll()
  }

  override protected def afterAll() {
    super.afterAll()
    logger.warn("**** > Finished " + this.getClass)
  }


  override protected def afterEach(): Unit = {
    super.afterEach()
    clearEvents()
    eventTimeout = EventWaitTimeout(8 seconds)
  }

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
    evtPublisher.asInstanceOf[TestSyseventPublisher].withOrderedEvents { events =>
      events.foreach { next =>
        log.error(buildEventLogMessage(next.timestamp, next.event, next.values))
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
    evtPublisher.asInstanceOf[TestSyseventPublisher].withOrderedEvents { events =>
      events.foreach { next =>
        log.error(buildEventLogMessage(next.timestamp, next.event, next.values))
      }
    }
    log2.error("*" * 120 + "\n\n\n\n")
  }

  private def checkForSpecificDuration(millis: Long)(f: => Unit) = {
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
        val ctx = evtPublisher.contextFor(ErrorSysevent("ExpectationFailed", "Test"), Seq())
        evtPublisher.publish(ctx)
        report(x)
        throw x
    }
  }

  def locateAllEvents(event: Sysevent) = {
    on anyNode expectSome of event
    events.get(event)
  }

  def locateLastEvent(event: Sysevent) = locateAllEvents(event).map(_.head)

  def locateFirstEvent(event: Sysevent) = locateAllEvents(event).map(_.head)

  def locateFirstEventFieldValue(event: Sysevent, field: String) = {
    val maybeValue = for (
      all <- locateAllEvents(event);
      first = all.head;
      (f, v) <- first.find { case (f, v) => f.name == field }
    ) yield v
    maybeValue.get
  }

  def locateLastEventFieldValue(event: Sysevent, field: String) = {
    val maybeValue = for (
      all <- locateAllEvents(event);
      first = all.last;
      (f, v) <- first.find { case (f, v) => f.name == field }
    ) yield v
    maybeValue.get
  }

  def within(duration: FiniteDuration)(f: => Unit): Unit = within(duration.toMillis)(f)

  def within(millis: Long)(f: => Unit):Unit = {
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < millis) {
      f
      Thread.sleep(50)
    }
  }

  private type EventCheck = (FiniteDuration, Sysevent, Seq[FieldAndValue]) => Unit

  private def eventsExpected(count: Range) =
    (timeout: FiniteDuration, event: Sysevent, values: Seq[FieldAndValue]) =>
      checkForSpecificDuration(timeout.toMillis) {
        val e = events
        e should contain key event
        e.get(event).get should haveAllValues(event, count, values)
      }

  private val eventsNotExpected = (timeout: FiniteDuration, event: Sysevent, values: Seq[FieldAndValue]) =>
      try {
        events.get(event).foreach(_ shouldNot haveAllValues(event, values))
      } catch {
        case x: Throwable =>
          report(x)
          throw x
      }


  case class BaseExpectation(check: EventCheck)

  def expect(r: Range): BaseExpectation = BaseExpectation(eventsExpected(r))

  def expect(i: Int): BaseExpectation = expect(i to i)

  def expectOne: BaseExpectation = expect(1)

  def expectSome: BaseExpectation = expect(1 to Int.MaxValue)

  def expectNone: BaseExpectation = BaseExpectation(eventsNotExpected)

  implicit def convertToEventSpec(e: Sysevent): EventSpec = EventSpec(e)

  case class EventSpec(e: Sysevent, fields: Seq[(Symbol, Any)] = Seq()) {
    def withFields(x: (Symbol, Any)*) = copy(fields = fields ++ x)

    def +(x: (Symbol, Any)*) = withFields(x: _*)
  }

  case class EventAssertionKey()

  val on = EventAssertionKey()

  case class EventWaitTimeout(duration: FiniteDuration)

  implicit var eventTimeout = EventWaitTimeout(15 seconds)

  case class ExecutableExpectation(expectation: BaseExpectation, requiredFields: Seq[(Symbol, Any)]) {
    def of(spec: EventSpec)(implicit t: EventWaitTimeout): Unit = expectation.check(t.duration, spec.e, spec.fields ++ requiredFields)

    def ofEach(specs: EventSpec*)(implicit t: EventWaitTimeout): Unit = specs foreach(of(_)(t))

  }

  case class OnAnyNode() {
    def anyNode(e: BaseExpectation): ExecutableExpectation = ExecutableExpectation(e, Seq())
  }

  implicit def convertToOnAnyNode(s: EventAssertionKey): OnAnyNode = OnAnyNode()

}
