package backend

import akka.actor.ActorSystem
import backend.utils.SystemMonitor
import com.typesafe.config.ConfigFactory

object BackendBootstrap extends App {

    implicit val sys = ActorSystem("backend", ConfigFactory.load("backend.conf"))

    PriceDatasource.start()

    SystemMonitor.start()

}
