package rs.core.services.endpoint.akkastreams

import akka.stream.scaladsl.BidiFlow
import akka.util.ByteString
import rs.core.config.{GlobalConfig, ServiceConfig}
import rs.core.services.Messages.{ServiceOutbound, ServiceInbound}
import rs.core.sysevents.WithSyseventPublisher

trait StageBuilder[I1,O1,I2,O2] {
  def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, globalConfig: GlobalConfig, pub: WithSyseventPublisher): Option[BidiFlow[I1,O1,I2,O2, Unit]]
}

trait BytesStageBuilder extends StageBuilder[ByteString, ByteString, ByteString, ByteString]

trait ProtocolDialectStageBuilder[DI,DO] extends StageBuilder[DI, DI, DO, DO]

trait ServiceDialectStageBuilder extends StageBuilder[ServiceInbound, ServiceInbound, ServiceOutbound, ServiceOutbound]
