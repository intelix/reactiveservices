package au.com.intelix.rs.core.actors

import akka.actor.{Actor, ActorRef, Terminated}
import au.com.intelix.config.RootConfig
import au.com.intelix.evt.EvtContext
import au.com.intelix.rs.core.config.WithActorSystemConfig

trait BaseActor extends WithActorSystemConfig with ActorUtils with EvtContext {

  import CommonActorEvt._

  private val pathAsString = self.path.toStringWithoutAddress
  protected[actors] var terminatedFuncChain: Seq[ActorRef => Unit] = Seq.empty

  private var preStartChain: Seq[() => Unit] = Seq.empty
  private var preRestartChain: Seq[PartialFunction[(Throwable, Option[Any]), Unit]] = Seq.empty
  private var postRestartChain: Seq[PartialFunction[Throwable, Unit]] = Seq.empty
  private var postStopChain: Seq[() => Unit] = Seq.empty

  override implicit lazy val nodeCfg: RootConfig = RootConfig(config)

  def onActorTerminated(f: ActorRef => Unit) = terminatedFuncChain = terminatedFuncChain :+ f
  def onPreStart(thunk: => Unit) = preStartChain = preStartChain :+ (() => thunk)
  def onPostStop(thunk: => Unit) = postStopChain = postStopChain :+ (() => thunk)
  def onPreRestart(f: PartialFunction[(Throwable, Option[Any]), Unit]) = preRestartChain = preRestartChain :+ f
  def onPostRestart(f: PartialFunction[Throwable, Unit]) = postRestartChain = postRestartChain :+ f

  commonEvtFields('path -> pathAsString, 'nodeid -> nodeId)

  @throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    raise(Evt.PreRestart, 'reason -> reason.getMessage, 'msg -> message, 'path -> pathAsString)
    preRestartChain.foreach(_.applyOrElse((reason, message), (_: (Throwable, Option[Any])) => () ))
    super.preRestart(reason, message)
  }

  @throws[Exception](classOf[Exception])
  override def postRestart(reason: Throwable): Unit = {
    postRestartChain.foreach(_.applyOrElse(reason, (_: Throwable) => ()))
    super.postRestart(reason)
    raise(Evt.PostRestart, 'reason -> reason.getMessage, 'path -> pathAsString)
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    raise(Evt.PreStart, 'path -> pathAsString)
    preStartChain.foreach(_.apply())
    super.preStart()
  }

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    postStopChain.foreach(_.apply())
    super.postStop()
    raise(Evt.PostStop, 'path -> pathAsString)
  }


  def onMessage(f: Receive)


}


trait JBaseActor extends BaseActor {

  private var chainedFunc: Receive = {
    case Terminated(ref) => terminatedFuncChain.foreach(_ (ref))
  }

  override final val receive: Actor.Receive = {
    case x if chainedFunc.isDefinedAt(x) => chainedFunc(x)
    case x => unhandled(x)
  }

  override final def onMessage(f: Receive): Unit = chainedFunc = f orElse chainedFunc
}
