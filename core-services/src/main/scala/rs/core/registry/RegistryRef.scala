package rs.core.registry

import akka.actor.ActorRef
import rs.core.ServiceKey
import rs.core.actors.ActorWithComposableBehavior
import rs.core.registry.Messages._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


trait RegistryRef extends ActorWithComposableBehavior {

  type LocationHandler = PartialFunction[(ServiceKey, Option[ActorRef]), Unit]
  // with healthy config, when registry is deployed on every node, this should be extremely fast lookup, so blocking wait should never be an issue
  private lazy val ref = Await.result(path.resolveOne(5 seconds), 5 seconds)
  private val path = context.actorSelection("/user/local-registry")
  private var localLocation: Map[ServiceKey, Option[ActorRef]] = Map.empty
  private var localLocationHandlerFunc: LocationHandler = {
    case _ =>
  }


  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    ref ! CancelAllStreamingLookups
    super.postStop()
  }

  final def lookupServiceLocation(s: ServiceKey): Option[ActorRef] = localLocation get s flatten

  final def unregisterService(s: ServiceKey) = unregisterServiceAt(s, self)
  final def registerService(s: ServiceKey) = registerServiceAt(s, self)

  final def registerServiceAt(s: ServiceKey, loc: ActorRef) = {
    logger.info(s"!>>>> Registering $s at $loc")
    ref ! Register(s, loc)
  }
  final def unregisterServiceAt(s: ServiceKey, loc: ActorRef) = {
    logger.info(s"!>>>> Registering $s at $loc")
    ref ! Unregister(s, loc)
  }

  final def registerServiceLocationInterest(s: ServiceKey) =
    if (!localLocation.contains(s.id)) {
      ref ! StreamingLookup(s)
      localLocation += s -> None
    }

  final def unregisterServiceLocationInterest(s: ServiceKey) =
    if (localLocation.contains(s)) {
      ref ! CancelStreamingLookup(s)
      localLocation -= s.id
    }

  final def onServiceLocationChanged(handler: LocationHandler) = localLocationHandlerFunc = handler orElse localLocationHandlerFunc

  onMessage {
    case LocationUpdate(name, maybeLocation) =>
      logger.info(s"!>>>>> LocalServiceLocation($name, $maybeLocation)")
      val was = localLocation.get(name).flatten
      if (was != maybeLocation) {
        localLocation += name -> maybeLocation
        localLocationHandlerFunc(name, maybeLocation)
      }
  }

}
