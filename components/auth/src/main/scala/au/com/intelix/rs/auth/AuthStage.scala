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
package au.com.intelix.rs.auth

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.config.RootConfig
import au.com.intelix.essentials.uuid.UUIDTools
import au.com.intelix.evt.{EvtContext, InfoE, WarningE}
import au.com.intelix.rs.core.SubjectTags.{StringSubjectTag, UserId, UserToken}
import au.com.intelix.rs.core.config.ServiceConfig
import au.com.intelix.rs.core.services.Messages._
import au.com.intelix.rs.core.services.endpoint.akkastreams.ServiceDialectStageBuilder
import au.com.intelix.rs.core.stream.{DictionaryMapStreamState, SetStreamState}
import au.com.intelix.rs.core.{ServiceKey, Subject, TopicKey}

import scala.collection.mutable

object AuthStage {

  object Evt {
    case object SubscribingToAuth extends InfoE
    case object UserIdReset extends InfoE
    case object UserIdProvided extends InfoE
    case object SubjectPermissionsProvided extends InfoE
    case object SubjectPermissionsReset extends InfoE
    case object AuthServiceDown extends WarningE
    case object AccessDenied extends WarningE
  }

}

private object PrivateIdToken extends StringSubjectTag("pid")

class AuthStage extends ServiceDialectStageBuilder {

  import AuthStage._

  private class AuthGraph(serviceCfg: ServiceConfig, nodeCfg: RootConfig, sessionId: String) extends GraphStage[BidiShape[ServiceInbound, ServiceInbound, ServiceOutbound, ServiceOutbound]] {
    val in1: Inlet[ServiceInbound] = Inlet("ServiceBoundIn")
    val out1: Outlet[ServiceInbound] = Outlet("ServiceBoundOut")
    val in2: Inlet[ServiceOutbound] = Inlet("ClientBoundIn")
    val out2: Outlet[ServiceOutbound] = Outlet("ClientBoundOut")

    override val shape: BidiShape[ServiceInbound, ServiceInbound, ServiceOutbound, ServiceOutbound] = BidiShape(in1, out1, in2, out2)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      val evtCtx = EvtContext(classOf[AuthStage], nodeCfg.config, 'token -> sessionId)

      val outBuffer = serviceCfg.asInt("auth.out-buffer-msg", defaultValue = 8)
      val inBuffer = serviceCfg.asInt("auth.in-buffer-msg", defaultValue = 8)

      val clientBound = mutable.Queue[ServiceOutbound]()
      val serviceBound = mutable.Queue[ServiceInbound]()

      val privateKey = sessionId + "_" + UUIDTools.generateUUID

      val authServiceKey: ServiceKey = serviceCfg.asString("auth.service-key", "auth")

      val tokenTopic: TopicKey = serviceCfg.asString("auth.topic-token", "token")
      val subjectsPermissionsTopic: TopicKey = serviceCfg.asString("auth.topic-subjects-permissions", "subjects")
      val infoTopic: TopicKey = serviceCfg.asString("auth.topic-info", "info")

      val closeOnServiceDown = serviceCfg.asBoolean("auth.invalidate-session-on-service-unavailable", defaultValue = true)

      val AuthSubSubject = Subject(authServiceKey, tokenTopic, UserToken(sessionId) + PrivateIdToken(privateKey))
      val SubjectPermissionsSubSubject = Subject(authServiceKey, subjectsPermissionsTopic, UserToken(sessionId) + PrivateIdToken(privateKey))
      val InfoSubSubject = Subject(authServiceKey, infoTopic, UserToken(sessionId) + PrivateIdToken(privateKey))


      var userId: Option[Int] = None
      var tags: Option[String] = None
      var permissions = Array[SubjectPermission]()

      override def preStart(): Unit = {
        pull(in1)
        pull(in2)
        evtCtx.raise(Evt.SubscribingToAuth, 'service -> authServiceKey)
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
            evtCtx.raise(Evt.AccessDenied, 'subj -> x.subj, 'userid -> userId)
            None
          }
        case x: Signal =>
          if (isAllowed(x.subj)) Some(x)
          else {
            evtCtx.raise(Evt.AccessDenied, 'subj -> x.subj, 'userid -> userId)
            None
          }
        case x => Some(x)
      }

      def filterLocalUpdates(s: ServiceOutbound): Option[ServiceOutbound] = s match {
        case ServiceNotAvailable(a) if a == authServiceKey =>
          evtCtx.raise(Evt.AuthServiceDown, 'service -> authServiceKey, 'clientAccessReset -> closeOnServiceDown)
          if (closeOnServiceDown) {
            userId = None
            tags = None
          }
          Some(s)
        case StreamStateUpdate(SubjectPermissionsSubSubject, SetStreamState(_, _, set, _)) =>
          permissions = convertPermissions(set.asInstanceOf[Set[String]])
          if (permissions.isEmpty) evtCtx.raise(Evt.SubjectPermissionsReset) else evtCtx.raise(Evt.SubjectPermissionsProvided)
          None
        case StreamStateUpdate(InfoSubSubject, DictionaryMapStreamState(_, _, values, d)) =>
          (d.locateIdx(AuthServiceActor.InfoUserId),d.locateIdx(AuthServiceActor.InfoUsername)) match {
            case (-1, _) =>
            case (_, -1) =>
            case (i,j) => (values(i),values(j)) match {
              case (v: Int, un: String) if v > 0 =>
                evtCtx.raise(Evt.UserIdProvided, 'id -> v, 'user -> un)
                userId = Some(v)
                tags = d.locateIdx(AuthServiceActor.InfoTags) match {
                  case -1 => None
                  case k => Some(values(k).asInstanceOf[String])
                }
              case _ =>
                evtCtx.raise(Evt.UserIdReset)
                userId = None
                tags = None
            }
          }
          None
        case StreamStateUpdate(subj@Subject(_, _, PrivateIdToken(k)), state) if k == privateKey =>
          None
        case _ => Some(s)
      }

      def enrich(s: ServiceInbound): ServiceInbound = s match {
        case s: OpenSubscription => s.copy(subj = s.subj + UserToken(sessionId) + userId.map(UserId(_)) + tags.getOrElse(""))
        case s: CloseSubscription => s.copy(subj = s.subj + UserToken(sessionId) + userId.map(UserId(_)) + tags.getOrElse(""))
        case s: Signal => s.copy(subj = s.subj + UserToken(sessionId) + userId.map(UserId(_)) + tags.getOrElse(""))
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


  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: RootConfig) =
    if (serviceCfg.asBoolean("auth.enabled", defaultValue = true)) Some(BidiFlow.fromGraph(new AuthGraph(serviceCfg, nodeCfg, sessionId))) else None


  private case class SubjectPermission(s: String) {
    val allow = !s.startsWith("-")
    val pattern = if (s.startsWith("+") || s.startsWith("-")) s.substring(1) else s

    def isMatching(key: String) = key == pattern || key.contains(pattern)
  }

}
