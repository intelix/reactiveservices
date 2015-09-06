package rs.service.auth

import akka.stream.BidiShape
import akka.stream.scaladsl.{Flow, FlowGraph, BidiFlow}
import rs.core.SubjectKeys.UserToken
import rs.core.services.Messages._

object AuthStage {


  def buildStage(id: String): BidiFlow[ServiceInboundMessage, ServiceInboundMessage, ServiceOutboundMessage, ServiceOutboundMessage, Unit] =
    BidiFlow() { b =>

      val in = b.add(Flow[ServiceInboundMessage].map {
        case s: OpenSubscription => s.copy(subj = s.subj.withKeys(UserToken(id)))
        case s: CloseSubscription => s.copy(subj = s.subj.withKeys(UserToken(id)))
        case s: Signal => s.copy(subj = s.subj.withKeys(UserToken(id)))
        case s => s
      })

      val out = b.add(Flow[ServiceOutboundMessage].map {
        case s: SubscriptionClosed => s.copy(subj = s.subj.copy(keys = ""))
        case s: InvalidRequest => s.copy(subj = s.subj.copy(keys = ""))
        case s: StreamStateUpdate => s.copy(subject = s.subject.copy(keys = ""))
        case s: SignalAckOk => s.copy(subj = s.subj.copy(keys = ""))
        case s: SignalAckFailed => s.copy(subj = s.subj.copy(keys = ""))
        case s => s
      })

      BidiShape(in, out)

    }




}
