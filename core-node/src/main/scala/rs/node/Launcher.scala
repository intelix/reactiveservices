package rs.node

import rs.core.sysevents.SyseventPublisherRef
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import rs.core.sysevents.{LoggerSyseventPublisher, SEvtSystem, SyseventPublisherRef, SyseventSystemRef}
import rs.node.core.ServiceClusterGuardianActor

object Launcher extends App {


  private val configName: String = java.lang.System.getProperty("config", "node.conf")
  private val localSystemConfigName: String = java.lang.System.getProperty("config", "node-local.conf")
  val config = ConfigFactory.load(configName)

  // TODO move to config
  SyseventPublisherRef.ref = LoggerSyseventPublisher
  SyseventSystemRef.ref = SEvtSystem("EventStreams.Engine")

  println("config = " + ConfigFactory.load("node.conf"))

  implicit val system = ActorSystem("local", ConfigFactory.load("node-local.conf"))

  ServiceClusterGuardianActor.start(config)

}
