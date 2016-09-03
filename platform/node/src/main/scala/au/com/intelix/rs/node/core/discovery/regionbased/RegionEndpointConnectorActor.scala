package au.com.intelix.rs.node.core.discovery.regionbased

import akka.actor.Status
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.{ConnectionContext, Http, HttpConnectionContext, HttpsConnectionContext}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.evt.{ErrorE, InfoE, TraceE, WarningE}
import au.com.intelix.rs.core.actors.{ActorState, StatefulActor}
import au.com.intelix.rs.node.core.discovery.regionbased.RegionEndpointConnectorActor.{States, _}
import au.com.intelix.sslconfig._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.{Failure, Success}

object RegionEndpointConnectorActor {

  object Evt {
    case object Cfg_Intervals extends InfoE
    case object Cfg_SSL extends InfoE
    case object ReceivedClusterState extends InfoE
    case object UnrecognisedMsg extends TraceE
    case object UnableToConsumeData extends ErrorE
    case object RemoteServiceFailure extends WarningE
    case object RequestFailed extends WarningE
    case object Query extends InfoE
  }

  object In {
    case object Close
    case object FastDiscovery
    case object PacedDiscovery
    case object ContinuousDiscovery
  }

  object Out {
    case class View(members: Set[String], roles: Set[String])
    case object Invalidate
  }

  case class LocalData(interval: FiniteDuration)

  private object States {
    case object Idle extends ActorState
    case object Querying extends ActorState
    case object Closing extends ActorState
  }

}

class RegionEndpointConnectorActor(regionId: String, endpoint: Endpoint) extends StatefulActor[LocalData] {

  import akka.pattern.pipe
  import context.dispatcher

  override def evtSourceSubId: Option[String] = Some(regionId)

  case class Data(b: Option[ByteString])

  implicit val sys = context.system

  val discoveryCfg = config.asConfig("node.cluster.discovery.region-based-http")
  val fastDiscoveryInterval = discoveryCfg.asFiniteDuration("query-interval-fast", 2 seconds)
  val pacedDiscoveryInterval = discoveryCfg.asFiniteDuration("query-interval-paced", 6 seconds)
  val continuousDiscoveryInterval = discoveryCfg.asFiniteDuration("query-interval-continuous", 20 seconds)
  val apiKey = discoveryCfg.asString("api-key", "none")

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  val http = Http()
  lazy val connectionCtx: ConnectionContext =
    SSLContextHelper.build(discoveryCfg.asConfig("ssl"), SSLContextDefaults.Client) match {
      case FailedSSLContext(error) => throw new Error(error)
      case DisabledSSLContext => ConnectionContext.noEncryption()
      case InitialisedSSLContext(sslContext, protos, algos) =>
        ConnectionContext.https(
          sslContext = sslContext,
          enabledProtocols = Some(protos),
          enabledCipherSuites = algos
        )
    }

  commonEvtFields('region -> regionId, 'endpoint -> s"${endpoint.host}:${endpoint.port}")
  evt(Evt.Cfg_Intervals, 'fast -> fastDiscoveryInterval, 'paced -> pacedDiscoveryInterval, 'cont -> continuousDiscoveryInterval)

  startWith(States.Querying, LocalData(fastDiscoveryInterval))

  when(States.Idle) {
    case Event(Internal.TimeToQuery, _) => transitionTo(States.Querying)
    case Event(In.Close, _) =>
      context.parent ! Out.Invalidate
      stop()
    case Event(In.FastDiscovery, data) => transitionTo(States.Querying) using data.copy(interval = fastDiscoveryInterval)

  }

  when(States.Querying) {
    case Event(In.Close, _) => transitionTo(States.Closing)
    case Event(Data(None), data) =>
      context.parent ! Out.Invalidate
      transitionTo(States.Idle)
    case Event(Data(Some(bs)), data) =>
      bs match {
        case msg@RemoteClusterView(mem, roles) =>
          evt(Evt.ReceivedClusterState, 'data -> msg)
          context.parent ! Out.View(mem, roles)
        case x =>
          context.parent ! Out.Invalidate
          evt(Evt.UnrecognisedMsg, 'msg -> x)
      }
      transitionTo(States.Idle)
  }

  when(States.Closing) {
    case Event(t: Data, _) =>
      context.parent ! Out.Invalidate
      stop()
  }

  otherwise {
    case Event(In.ContinuousDiscovery, data) =>
      stay() using data.copy(interval = continuousDiscoveryInterval)
    case Event(In.PacedDiscovery, data) =>
      stay() using data.copy(interval = pacedDiscoveryInterval)
    case Event(In.FastDiscovery, data) => stay() using data.copy(interval = fastDiscoveryInterval)
    case Event(HttpResponse(StatusCodes.OK, headers, entity, _), data) =>
      val data = entity.dataBytes.runFold(ByteString(""))(_ ++ _)
      data.onComplete {
        case Success(d) => self ! Data(Some(d))
        case Failure(e) =>
          evt(Evt.UnableToConsumeData, 'error -> e)
          self ! Data(None)
      }
      stay()
    case Event(HttpResponse(status, _, _, _), _) =>
      evt(Evt.RemoteServiceFailure, 'status -> status)
      self ! Data(None)
      stay()
    case Event(Status.Failure(e), _) =>
      evt(Evt.RequestFailed, 'error -> e)
      self ! Data(None)
      stay()
    case Event(Internal.TimeToQuery, _) => stay()
  }

  onTransition {
    case _ -> States.Idle =>
      setTimer("trigger", Internal.TimeToQuery, nextStateData.interval, repeat = false)
    case _ -> States.Querying =>
      val future = connectionCtx match {
        case ctx: HttpsConnectionContext =>
          val uri = s"https://${endpoint.host}:${endpoint.port}/api/cluster/state"
          evt(Evt.Query, 'uri -> uri)
          http.singleRequest(request = HttpRequest(uri = uri).withHeaders(RawHeader("Api-Key", apiKey)), connectionContext = ctx)
        case ctx: HttpConnectionContext =>
          val uri = s"http://${endpoint.host}:${endpoint.port}/api/cluster/state"
          evt(Evt.Query, 'uri -> uri)
          http.singleRequest(request = HttpRequest(uri = uri).withHeaders(RawHeader("Api-Key", apiKey)))
      }
      future.pipeTo(self)

  }

  object Internal {

    case object TimeToQuery

  }

}

