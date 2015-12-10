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
import rs.core.SubjectTags.{SubjectTag, UserId, UserToken}
import rs.core.config.ConfigOps.wrap
import rs.core.config.{NodeConfig, ServiceConfig}
import rs.core.services.Messages._
import rs.core.services.endpoint.akkastreams.ServiceDialectStageBuilder
import rs.core.stream.{DictionaryMapStreamState, SetStreamState}
import rs.core.sysevents.{CommonEvt, EvtPublisher}
import rs.core.tools.UUIDTools
import rs.core.{ServiceKey, Subject, TopicKey}

trait AuthStageEvt extends CommonEvt {

  val SubscribingToAuth = "SubscribingToAuth".info
  val UserIdReset = "UserIdReset".info
  val UserIdProvided = "UserIdProvided".info
  val SubjectPermissionsProvided = "SubjectPermissionsProvided".info
  val SubjectPermissionsReset = "SubjectPermissionsReset".info
  val AuthServiceDown = "AuthServiceDown".warn
  val AccessDenied = "AccessDenied".warn

  override def componentId: String = "Endpoint.Auth"
}

object AuthStageEvt extends AuthStageEvt

private object PrivateIdToken extends SubjectTag("pid")

class AuthStage extends ServiceDialectStageBuilder {

  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: NodeConfig) =
    if (serviceCfg.asBoolean("auth.enabled", defaultValue = true))
      Some(BidiFlow.wrap(FlowGraph.partial() { implicit b =>
        import FlowGraph.Implicits._

        implicit val evtPub = EvtPublisher(nodeCfg, 'token -> sessionId)

        val privateKey = sessionId + "_" + UUIDTools.generateShortUUID

        val authServiceKey: ServiceKey = serviceCfg.asString("auth.service-key", "auth")

        val tokenTopic: TopicKey = serviceCfg.asString("auth.topic-token", "token")
        val subjectsPermissionsTopic: TopicKey = serviceCfg.asString("auth.topic-subjects-permissions", "subjects")
        val infoTopic: TopicKey = serviceCfg.asString("auth.topic-info", "info")

        val closeOnServiceDown = serviceCfg.asBoolean("auth.invalidate-session-on-service-unavailable", defaultValue = true)

        val AuthSubSubject = Subject(authServiceKey, tokenTopic, UserToken(sessionId) + PrivateIdToken(privateKey))
        val SubjectPermissionsSubSubject = Subject(authServiceKey, subjectsPermissionsTopic, UserToken(sessionId) + PrivateIdToken(privateKey))
        val InfoSubSubject = Subject(authServiceKey, infoTopic, UserToken(sessionId) + PrivateIdToken(privateKey))


        @volatile var userId: Option[String] = None
        @volatile var permissions = Array[SubjectPermission]()

        def isAllowed(s: Subject) = authServiceKey == s.service || permissions.find(_.isMatching(s.service.id + "/" + s.topic.id)).exists(_.allow)

        val in = b.add(Flow[ServiceInbound]
          .filter {
            case s: OpenSubscription =>
              val allow = isAllowed(s.subj)
              if (!allow) AuthStageEvt.AccessDenied('subj -> s.subj, 'userid -> userId)
              allow
            case s: CloseSubscription => true
            case s: Signal =>
              val allow = isAllowed(s.subj)
              if (!allow) AuthStageEvt.AccessDenied('subj -> s.subj, 'userid -> userId)
              allow
          }
          .map {
            case s: OpenSubscription =>
              s.copy(subj = s.subj + UserToken(sessionId) + userId.map(UserId(_)))
            case s: CloseSubscription =>
              s.copy(subj = s.subj + UserToken(sessionId) + userId.map(UserId(_)))
            case s: Signal =>
              s.copy(subj = s.subj + UserToken(sessionId) + userId.map(UserId(_)))
          })

        val out = b.add(Flow[ServiceOutbound].filter {
          case ServiceNotAvailable(a) if a == authServiceKey =>
            AuthStageEvt.AuthServiceDown('service -> authServiceKey, 'clientAccessReset -> closeOnServiceDown)
            if (closeOnServiceDown) userId = None
            true
          case StreamStateUpdate(SubjectPermissionsSubSubject, SetStreamState(_, _, set, _)) =>
            permissions = convertPermissions(set)
            if (permissions.isEmpty) AuthStageEvt.SubjectPermissionsReset() else AuthStageEvt.SubjectPermissionsProvided()
            false
          case StreamStateUpdate(InfoSubSubject, DictionaryMapStreamState(_, _, values, d)) =>
            d.locateIdx(AuthServiceActor.InfoUserId) match {
              case -1 =>
              case i => values(i) match {
                case v: String if v != "" =>
                  AuthStageEvt.UserIdProvided('id -> v)
                  userId = Some(v)
                case _ =>
                  AuthStageEvt.UserIdReset()
                  userId = None
              }
            }
            false
          case StreamStateUpdate(subj@Subject(_, _, PrivateIdToken(k)), state) if k == privateKey =>
            false
          case _ => true
        }.map {
          case s: SubscriptionClosed => s.copy(subj = s.subj.removeTags())
          case s: InvalidRequest => s.copy(subj = s.subj.removeTags())
          case s: StreamStateUpdate => s.copy(subject = s.subject.removeTags())
          case s: SignalAckOk => s.copy(subj = s.subj.removeTags())
          case s: SignalAckFailed => s.copy(subj = s.subj.removeTags())
          case s: ServiceNotAvailable => s
        })

        val s = Source({
          AuthStageEvt.SubscribingToAuth('service -> authServiceKey)
          List(
            OpenSubscription(AuthSubSubject),
            OpenSubscription(SubjectPermissionsSubSubject),
            OpenSubscription(InfoSubSubject))
        })
        val concat = b.add(Concat[ServiceInbound])

        s ~> concat
        in ~> concat

        BidiShape(FlowShape(in.inlet, concat.out), out)
      }))
    else None

  private def convertPermissions(set: Set[String]) = set.map(SubjectPermission).toArray.sortBy { x => -x.s.length * (if (!x.allow) 100 else 1) }

  private case class SubjectPermission(s: String) {
    val allow = !s.startsWith("-")
    val pattern = if (s.startsWith("+") || s.startsWith("-")) s.substring(1) else s

    def isMatching(key: String) = key == pattern || key.contains(pattern)
  }

}
