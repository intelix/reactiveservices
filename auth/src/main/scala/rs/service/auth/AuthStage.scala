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
import rs.core.SubjectKeys.{KeyOps, UserId, UserToken}
import rs.core.config.ConfigOps.wrap
import rs.core.config.{ConfigOps, GlobalConfig, ServiceConfig}
import rs.core.services.Messages._
import rs.core.stream.StringStreamState
import rs.core.sysevents.WithSyseventPublisher
import rs.core.sysevents.ref.ComponentWithBaseSysevents
import rs.core.{ServiceKey, Subject, TopicKey}

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

  private object SecretToken extends KeyOps {
    override val token: String = "secret"
  }


  def buildStage(userToken: String, componentId: String)
                (implicit gCfg: GlobalConfig, sCfg: ServiceConfig, pub: WithSyseventPublisher): BidiFlow[ServiceInbound, ServiceInbound, ServiceOutbound, ServiceOutbound, Unit] =
    BidiFlow.wrap(FlowGraph.partial() { implicit b =>
      import FlowGraph.Implicits._

      val Events = new AuthStageSyseventsAt(componentId)

      val PrivateKey = userToken + "_private"

      val AuthService: ServiceKey = sCfg.asString("auth-client.service-key", "auth")

      val TokenTopic: TopicKey = sCfg.asString("auth-client.topic-token", "token")
      val PermissionsTopic: TopicKey = sCfg.asString("auth-client.topic-permissions", "permissions")
      val InfoTopic: TopicKey = sCfg.asString("auth-client.topic-info", "info")

      val closeOnServiceDown = sCfg.asBoolean("auth-stage.close-when-auth-unavailable", defaultValue = true)

      val AuthSubSubject = Subject(AuthService, TokenTopic, UserToken(userToken) + SecretToken(PrivateKey))
      val PermissionsSubSubject = Subject(AuthService, PermissionsTopic, UserToken(userToken) + SecretToken(PrivateKey))
      val InfoSubSubject = Subject(AuthService, InfoTopic, UserToken(userToken) + SecretToken(PrivateKey))


      @volatile var userId: Option[String] = None

      val in = b.add(Flow[ServiceInbound].map {
        case s: OpenSubscription =>
          s.copy(subj = s.subj + UserToken(userToken) + userId.map(UserId(_)))
        case s: CloseSubscription =>
          s.copy(subj = s.subj + UserToken(userToken) + userId.map(UserId(_)))
        case s: Signal =>
          s.copy(subj = s.subj + UserToken(userToken) + userId.map(UserId(_)))
      })

      val out = b.add(Flow[ServiceOutbound].filter {
        case ServiceNotAvailable(AuthService) =>
          Events.AuthServiceDown('service -> AuthService, 'clientAccessReset -> closeOnServiceDown)
          if (closeOnServiceDown) userId = None
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
        case s: SubscriptionClosed => s.copy(subj = s.subj.removeKeys())
        case s: InvalidRequest => s.copy(subj = s.subj.removeKeys())
        case s: StreamStateUpdate => s.copy(subject = s.subject.removeKeys())
        case s: SignalAckOk => s.copy(subj = s.subj.removeKeys())
        case s: SignalAckFailed => s.copy(subj = s.subj.removeKeys())
        case s: ServiceNotAvailable => s
      })

      val s = Source({
        Events.SubscribingToAuth('token -> userToken, 'service -> AuthService)
        List(
          OpenSubscription(AuthSubSubject),
          OpenSubscription(PermissionsSubSubject),
          OpenSubscription(InfoSubSubject))
      })
      val concat = b.add(Concat[ServiceInbound])

      s ~> concat
      in ~> concat

      BidiShape(FlowShape(in.inlet, concat.out), out)
    })


}
