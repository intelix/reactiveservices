package rs.testing

import org.scalatest.{BeforeAndAfterEach, Suite}
import org.slf4j.LoggerFactory

trait WithTestSeparator extends BeforeAndAfterEach {

  self: Suite =>

  override protected def beforeEach(): Unit = {
    LoggerFactory.getLogger("testseparator").debug("\n" * 3 + "-" * 120)
    super.beforeEach()
  }

  override protected def afterEach(): Unit = {
    LoggerFactory.getLogger("testseparator").debug(" " * 10 + "~" * 40 + " test finished " + "~" * 40)
    super.afterEach()
  }


}
