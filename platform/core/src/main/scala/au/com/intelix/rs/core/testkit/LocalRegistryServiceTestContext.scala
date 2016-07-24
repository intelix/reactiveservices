package au.com.intelix.rs.core.testkit

import akka.actor.Props
import au.com.intelix.rs.core.registry.ServiceRegistryActor

trait LocalRegistryServiceTestContext {

  def startRegistry()(implicit sys: ActorSystemWrapper) = sys.start(Props(classOf[ServiceRegistryActor], "local-registry"), "local-registry")



}
