package rs.testkit

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}

trait ActorSystemManagement

trait IsolatedActorSystems extends ActorSystemManagement with BeforeAndAfterEach {

  self: Suite with MultiActorSystemTestContext =>

  override protected def afterEach(): Unit = {
    destroyAllSystems()
    super.afterEach()
  }
}

trait SharedActorSystem extends ActorSystemManagement with BeforeAndAfterEach with BeforeAndAfterAll {

  self: Suite with MultiActorSystemTestContext =>

  override protected def afterAll(): Unit = {
    destroyAllSystems()
    super.afterAll()
  }

  override protected def afterEach(): Unit = {
    destroyAllActors()
    super.afterEach()
  }

}