package au.com.intelix.rs.core.serializer

import akka.actor.{ActorSystem, ExtendedActorSystem, ExtensionId, ExtensionIdProvider}

object AkkaRSSerializationExtension extends ExtensionId[AkkaSerializer] with ExtensionIdProvider {
  override def get(system: ActorSystem): AkkaSerializer = super.get(system)

  override def lookup = AkkaRSSerializationExtension

  override def createExtension(system: ExtendedActorSystem): AkkaSerializer = new AkkaSerializer(system)
}
