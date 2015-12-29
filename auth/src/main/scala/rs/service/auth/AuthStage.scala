/*
 * Copyright 2014-16 Intelix Pty Ltd
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

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import rs.core.SubjectTags.{SubjectTag, UserId, UserToken}
import rs.core.config.ConfigOps.wrap
import rs.core.config.{NodeConfig, ServiceConfig}
import rs.core.services.Messages._
import rs.core.services.endpoint.akkastreams.ServiceDialectStageBuilder
import rs.core.stream.{DictionaryMapStreamState, SetStreamState}
import rs.core.sysevents.{CommonEvt, EvtPublisher}
import rs.core.tools.UUIDTools
import rs.core.{ServiceKey, Subject, TopicKey}

import scala.collection.mutable

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

  private class AuthGraph(serviceCfg: ServiceConfig, nodeCfg: NodeConfig, sessionId: String) extends GraphStage[BidiShape[ServiceInbound, ServiceInbound, ServiceOutbound, ServiceOutbound]] {
    val in1: Inlet[ServiceInbound] = Inlet("ServiceBoundIn")
    val out1: Outlet[ServiceInbound] = Outlet("ServiceBoundOut")
    val in2: Inlet[ServiceOutbound] = Inlet("ClientBoundIn")
    val out2: Outlet[ServiceOutbound] = Outlet("ClientBoundOut")

    override val shape: BidiShape[ServiceInbound, ServiceInbound, ServiceOutbound, ServiceOutbound] = BidiShape(in1, out1, in2, out2)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      implicit val evtPub = EvtPublisher(nodeCfg, 'token -> sessionId)

      val outBuffer = serviceCfg.asInt("auth.out-buffer-msg", defaultValue = 8)
      val inBuffer = serviceCfg.asInt("auth.in-buffer-msg", defaultValue = 8)

      val clientBound = mutable.Queue[ServiceOutbound]()
      val serviceBound = mutable.Queue[ServiceInbound]()

      val privateKey = sessionId + "_" + UUIDTools.generateShortUUID

      val authServiceKey: ServiceKey = serviceCfg.asString("auth.service-key", "auth")

      val tokenTopic: TopicKey = serviceCfg.asString("auth.topic-token", "token")
      val subjectsPermissionsTopic: TopicKey = serviceCfg.asString("auth.topic-subjects-permissions", "subjects")
      val infoTopic: TopicKey = serviceCfg.asString("auth.topic-info", "info")

      val closeOnServiceDown = serviceCfg.asBoolean("auth.invalidate-session-on-service-unavailable", defaultValue = true)

      val AuthSubSubject = Subject(authServiceKey, tokenTopic, UserToken(sessionId) + PrivateIdToken(privateKey))
      val SubjectPermissionsSubSubject = Subject(authServiceKey, subjectsPermissionsTopic, UserToken(sessionId) + PrivateIdToken(privateKey))
      val InfoSubSubject = Subject(authServiceKey, infoTopic, UserToken(sessionId) + PrivateIdToken(privateKey))


      var userId: Option[String] = None
      var permissions = Array[SubjectPermission]()

      override def preStart(): Unit = {
        pull(in1)
        pull(in2)
        AuthStageEvt.SubscribingToAuth('service -> authServiceKey)
        serviceBound.enqueue(
          OpenSubscription(AuthSubSubject),
          OpenSubscription(SubjectPermissionsSubSubject),
          OpenSubscription(InfoSubSubject))
      }

      setHandler(in1, new InHandler {
        override def onPush(): Unit = {
          for (validated <- validateAccess(grab(in1))) pushToServer(enrich(validated))
          if (canPullFromClientNow) pull(in1)
        }
      })
      setHandler(out1, new OutHandler {
        override def onPull(): Unit = if (serviceBound.nonEmpty) {
          push(out1, serviceBound.dequeue())
          if (canPullFromClientNow) pull(in1)
        }
      })
      setHandler(in2, new InHandler {
        override def onPush(): Unit = {
          for (forClient <- filterLocalUpdates(grab(in2))) pushToClient(strip(forClient))
          if (canPullFromServiceNow) pull(in2)
        }
      })
      setHandler(out2, new OutHandler {
        override def onPull(): Unit = if (clientBound.nonEmpty) {
          push(out2, clientBound.dequeue())
          if (canPullFromServiceNow) pull(in2)
        }
      })

      def isAllowed(s: Subject) = authServiceKey == s.service || permissions.find(_.isMatching(s.service.id + "/" + s.topic.id)).exists(_.allow)


      def validateAccess(s: ServiceInbound): Option[ServiceInbound] = s match {
        case x: OpenSubscription =>
          if (isAllowed(x.subj)) Some(x)
          else {
            AuthStageEvt.AccessDenied('subj -> x.subj, 'userid -> userId)
            None
          }
        case x: Signal =>
          if (isAllowed(x.subj)) Some(x)
          else {
            AuthStageEvt.AccessDenied('subj -> x.subj, 'userid -> userId)
            None
          }
        case x => Some(x)
      }

      def filterLocalUpdates(s: ServiceOutbound): Option[ServiceOutbound] = s match {
        case ServiceNotAvailable(a) if a == authServiceKey =>
          AuthStageEvt.AuthServiceDown('service -> authServiceKey, 'clientAccessReset -> closeOnServiceDown)
          if (closeOnServiceDown) userId = None
          Some(s)
        case StreamStateUpdate(SubjectPermissionsSubSubject, SetStreamState(_, _, set, _)) =>
          permissions = convertPermissions(set)
          if (permissions.isEmpty) AuthStageEvt.SubjectPermissionsReset() else AuthStageEvt.SubjectPermissionsProvided()
          None
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
          None
        case StreamStateUpdate(subj@Subject(_, _, PrivateIdToken(k)), state) if k == privateKey =>
          None
        case _ => Some(s)
      }

      def enrich(s: ServiceInbound): ServiceInbound = s match {
        case s: OpenSubscription => s.copy(subj = s.subj + UserToken(sessionId) + userId.map(UserId(_)))
        case s: CloseSubscription => s.copy(subj = s.subj + UserToken(sessionId) + userId.map(UserId(_)))
        case s: Signal => s.copy(subj = s.subj + UserToken(sessionId) + userId.map(UserId(_)))
      }

      def strip(s: ServiceOutbound): ServiceOutbound = s match {
        case s: SubscriptionClosed => s.copy(subj = s.subj.removeTags())
        case s: InvalidRequest => s.copy(subj = s.subj.removeTags())
        case s: StreamStateUpdate => s.copy(subject = s.subject.removeTags())
        case s: SignalAckOk => s.copy(subj = s.subj.removeTags())
        case s: SignalAckFailed => s.copy(subj = s.subj.removeTags())
        case s: ServiceNotAvailable => s
      }

      private def convertPermissions(set: Set[String]) = set.map(SubjectPermission).toArray.sortBy { x => -x.s.length * (if (!x.allow) 100 else 1) }


      def pushToClient(x: ServiceOutbound) = {
        if (isAvailable(out2) && clientBound.isEmpty) push(out2, x)
        else clientBound.enqueue(x)
        if (canPullFromServiceNow) pull(in2)
      }

      def pushToServer(x: ServiceInbound) = {
        if (isAvailable(out1) && serviceBound.isEmpty) push(out1, x)
        else serviceBound.enqueue(x)
        if (canPullFromClientNow) pull(in1)
      }

      def canPullFromServiceNow = !hasBeenPulled(in2) && clientBound.size < outBuffer

      def canPullFromClientNow = !hasBeenPulled(in1) && serviceBound.size < inBuffer

    }
  }


  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: NodeConfig) =
    if (serviceCfg.asBoolean("auth.enabled", defaultValue = true)) Some(BidiFlow.fromGraph(new AuthGraph(serviceCfg, nodeCfg, sessionId))) else None


  private case class SubjectPermission(s: String) {
    val allow = !s.startsWith("-")
    val pattern = if (s.startsWith("+") || s.startsWith("-")) s.substring(1) else s

    def isMatching(key: String) = key == pattern || key.contains(pattern)
  }

}
