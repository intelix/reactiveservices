package au.com.intelix.rs.core.services.endpoint

import akka.actor.ActorRef
import akka.pattern.Patterns
import au.com.intelix.rs.core.Subject
import au.com.intelix.rs.core.actors.BaseActor
import au.com.intelix.rs.core.services.Messages.Signal
import au.com.intelix.rs.core.services.internal.SignalPort

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.{implicitConversions, postfixOps}


trait SignalProducer {

  _: BaseActor =>

  val signalPort: ActorRef = context.actorOf(SignalPort.props, "signal-port")


  final def signal(subj: Subject, payload: Any = None, expiry: FiniteDuration = 1 minute, orderingGroup: Option[Any] = None, correlationId: Option[Any] = None) = {
    signalPort ! Signal(subj, payload, now + expiry.toMillis, orderingGroup, correlationId)
  }

  final def signalAsk(subj: Subject, payload: Any = None, expiry: FiniteDuration = 1 minute, orderingGroup: Option[Any] = None, correlationId: Option[Any] = None) =
    Patterns.ask(signalPort, Signal(subj, payload, now + expiry.toMillis, orderingGroup, correlationId), expiry.toMillis)


}
