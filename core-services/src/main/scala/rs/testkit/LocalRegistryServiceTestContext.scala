package rs.testkit

import akka.actor.Props
import rs.core.registry.ServiceRegistryActor

trait LocalRegistryServiceTestContext {

  def startRegistry()(implicit sys: ActorSystemWrapper) = sys.start(Props(classOf[ServiceRegistryActor], "local-registry"), "local-registry")



}
