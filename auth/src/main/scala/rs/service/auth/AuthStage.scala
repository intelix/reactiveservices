/*
 * Copyright 2014-15 Intelix Pty Ltd
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
package rs.service.auth

import akka.stream.scaladsl._
import akka.stream.{BidiShape, FlowShape}
import rs.core.{ServiceKey, Subject}
import rs.core.SubjectKeys.{KeyOps, UserId, UserToken}
import rs.core.services.Messages._
import rs.core.stream.StringStreamState
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents

trait AuthStageSysevents extends ComponentWithBaseSysevents {

  val SubscribingToAuth = "SubscribingToAuth".info
  val UserIdReset = "UserIdReset".info
  val UserIdProvided = "UserIdProvided".info
  val AuthServiceDown = "AuthServiceDown".warn

}

class AuthStageSyseventsAt(parentComponentId: String) extends AuthStageSysevents {
  override def componentId: String = parentComponentId + ".AuthStage"
}

object AuthStage {


  def buildStage(userToken: String, componentId: String)(implicit pub: WithSyseventPublisher): BidiFlow[ServiceInboundMessage, ServiceInboundMessage, ServiceOutboundMessage, ServiceOutboundMessage, Unit] =
    BidiFlow.wrap(FlowGraph.partial() { implicit b =>
      import FlowGraph.Implicits._

      val Events = new AuthStageSyseventsAt(componentId)

      val PrivateKey = userToken + "_private"

      val AuthService: ServiceKey = "auth"

      @volatile var userId: Option[String] = None

      object SecretToken extends KeyOps {
        override val token: String = "secret"
      }

      val in = b.add(Flow[ServiceInboundMessage].map {
        case s: OpenSubscription =>
          s.copy(subj = s.subj.withKeys(UserToken(userToken)).withKeys(userId.map(UserId(_))))
        case s: CloseSubscription =>
          s.copy(subj = s.subj.withKeys(UserToken(userToken)).withKeys(userId.map(UserId(_))))
        case s: Signal =>
          s.copy(subj = s.subj.withKeys(UserToken(userToken)).withKeys(userId.map(UserId(_))))
      })

      val AuthSubSubject = Subject(AuthService, "token", UserToken(userToken) + SecretToken(PrivateKey))
      val PermissionsSubSubject = Subject(AuthService, "permissions", UserToken(userToken) + SecretToken(PrivateKey))
      val InfoSubSubject = Subject(AuthService, "info", UserToken(userToken) + SecretToken(PrivateKey))

      val out = b.add(Flow[ServiceOutboundMessage].filter {
        case ServiceNotAvailable(AuthService) =>
          Events.AuthServiceDown('service -> AuthService)
          userId = None
          true
        case StreamStateUpdate(InfoSubSubject, StringStreamState(id)) =>
          id match {
            case "" =>
              Events.UserIdReset('token -> userToken)
              userId = None
            case v =>
              Events.UserIdProvided('token -> userToken, 'id -> v)
              userId = Some(v)
          }
          false
        case StreamStateUpdate(subj@Subject(_, _, SecretToken(PrivateKey)), state) =>
          false
        case _ => true
      }.map {
        case s: SubscriptionClosed => s.copy(subj = s.subj.copy(keys = ""))
        case s: InvalidRequest => s.copy(subj = s.subj.copy(keys = ""))
        case s: StreamStateUpdate => s.copy(subject = s.subject.copy(keys = ""))
        case s: SignalAckOk => s.copy(subj = s.subj.copy(keys = ""))
        case s: SignalAckFailed => s.copy(subj = s.subj.copy(keys = ""))
        case s: ServiceNotAvailable => s
      })

      val s = Source({
        Events.SubscribingToAuth('token -> userToken, 'service -> AuthService)
        List(
          OpenSubscription(AuthSubSubject),
          OpenSubscription(PermissionsSubSubject),
          OpenSubscription(InfoSubSubject))
      })
      val concat = b.add(Concat[ServiceInboundMessage])

      s ~> concat
      in ~> concat

      BidiShape(FlowShape(in.inlet, concat.out), out)
    })


}
