package rs.core.codec.binary

import rs.core.codec.binary.BinaryProtocolMessages.{BinaryDialectInbound, BinaryDialectOutbound}
import rs.core.services.endpoint.akkastreams.ProtocolDialectStageBuilder

trait BinaryDialectStageBuilder extends ProtocolDialectStageBuilder[BinaryDialectInbound, BinaryDialectOutbound]

