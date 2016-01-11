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
package rs.testkit

import java.text.SimpleDateFormat
import java.util.Date

import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import org.slf4j.LoggerFactory
import rs.core.evt.{Evt, EvtFieldValue, EvtSource, StringEvtSource}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.{implicitConversions, postfixOps}

trait EvtAssertions extends Matchers with TestEvtContext with EvtMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  self: org.scalatest.Suite =>

  private val logger = LoggerFactory.getLogger("test")

  private val logFormat = "%35s - %-30s : %s"
  private val fieldPrefix = "#"
  private val fieldPostfix = "="
  private val fieldsSeparator = "  "

  private def buildEventLogMessage(source: EvtSource, e: Evt, values: Seq[EvtFieldValue]): String = {
    val fields = values.foldLeft(new StringBuilder) {
      (aggr, next) => aggr.append(fieldPrefix).append(next._1).append(fieldPostfix).append(next._2).append(fieldsSeparator)
    }
    logFormat.format(source.evtSourceId, e.name, fields.toString())
  }

  private def buildEventLogMessage(timestamp: Long, source: EvtSource, e: Evt, values: Seq[EvtFieldValue]): String = {

    val date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    val d = date.format(new Date(timestamp))

    d + ": " + buildEventLogMessage(source, e, values)
  }

  def publisher = TestEvtPublisher

  def clearEvents() = publisher.clear()

  def clearComponentEvents(componentId: String) = publisher.clearComponentEvents(componentId)


  override protected def beforeAll(): Unit = {
    logger.info(("*" * 10) + "Starting " + this.getClass)
    super.beforeAll()
  }

  override protected def afterAll() {
    super.afterAll()
    logger.warn(("*" * 10) + "Finished " + this.getClass)
  }

  implicit def fd2ewt(d: FiniteDuration): EventWaitTimeout = EventWaitTimeout(d)

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
    logger.info("*" * 60 + " RAISED EVENTS: " + "*" * 60)
    publisher.withOrderedEvents { events =>
      events.foreach { next =>
        logger.info(buildEventLogMessage(next.timestamp, next.source, next.event, next.values))
      }
    }
    logger.info("*" * 120 + "\n" * 4)
  }

  def report(x: Throwable) = {
    logger.error("Test failed", x)
    printRaisedEvents()
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
        report(x)
        throw x
    }
  }

  def locateAllEvents(s: EvtSelection): List[RaisedEvent] = {
    on anyNode expectSome of s
    publisher.eventsFor(s)
  }

  def locateLastEvent(event: EvtSelection): RaisedEvent = locateAllEvents(event).reverse.head

  def locateFirstEvent(event: EvtSelection): RaisedEvent = locateAllEvents(event).head

  def locateFirstEventFieldValue[T](event: EvtSelection, field: String): T =
    locateFirstEvent(event).values.find(_._1 == field).get._2.asInstanceOf[T]

  def locateFirstEventFieldValue[T](event: Evt, field: String): T = locateFirstEventFieldValue(EvtSelection(event, None), field)


  def locateLastEventFieldValue[T](event: EvtSelection, field: String): T =
    locateLastEvent(event).values.find(_._1 == field).get._2.asInstanceOf[T]

  def locateLastEventFieldValue[T](event: Evt, field: String): T = locateLastEventFieldValue(EvtSelection(event, None), field)


  def within(duration: FiniteDuration)(f: => Unit): Unit = within(duration.toMillis)(f)

  def within(millis: Long)(f: => Unit): Unit = {
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < millis) {
      f
      Thread.sleep(50)
    }
  }

  private type EventCheck = (FiniteDuration, EvtSelection, Seq[EvtFieldValue]) => Unit

  private def eventsExpected(count: Range) =
    (timeout: FiniteDuration, event: EvtSelection, values: Seq[EvtFieldValue]) =>
      checkForSpecificDuration(timeout.toMillis) {
        val e = publisher.eventsFor(event)
        if (e.isEmpty) fail(s"Event has not been raised: $event")
        e should haveAllValues(event, count, values)
      }

  private val eventsNotExpected = (timeout: FiniteDuration, event: EvtSelection, values: Seq[EvtFieldValue]) =>
    try {
      publisher.eventsFor(event) shouldNot haveAllValues(event, values)
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

  implicit def convertToEventSpec(e: EvtSelection): EventSpec = EventSpec(e)

  implicit def convertToEventSpec(e: Evt): EventSpec = EventSpec(EvtSelection(e, None))

  case class EventSpec(e: EvtSelection, fields: Seq[EvtFieldValue] = Seq()) {
    def withFields(x: EvtFieldValue*) = copy(fields = fields ++ x)

    def +(x: EvtFieldValue*) = withFields(x: _*)

    def +(x: String): EventSpec = this.+(StringEvtSource(x))

    def +(x: EvtSource): EventSpec = copy(e = e.copy(s = Some(x)))
  }

  case class EventAssertionKey()

  val on = EventAssertionKey()

  case class EventWaitTimeout(duration: FiniteDuration)

  implicit var eventTimeout: EventWaitTimeout = 15 seconds

  case class ExecutableExpectation(expectation: BaseExpectation, requiredFields: Seq[EvtFieldValue]) {

    def of(spec: EventSpec)(implicit t: EventWaitTimeout): Unit = expectation.check(t.duration, spec.e, spec.fields ++ requiredFields)

    def ofEach(specs: EventSpec*)(implicit t: EventWaitTimeout): Unit = specs foreach (of(_)(t))

  }

  case class OnAnyNode() {
    def anyNode(e: BaseExpectation): ExecutableExpectation = ExecutableExpectation(e, Seq())
  }

  implicit def convertToOnAnyNode(s: EventAssertionKey): OnAnyNode = OnAnyNode()

}
