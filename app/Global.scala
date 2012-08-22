import actors.{SprayHttpClientActor, HttpClientActor, BuildMonitoringSupervisor}
import akka.actor.{Props, DeadLetter, Actor}
import cc.spray.can.client.HttpClient
import cc.spray.io.IoWorker
import com.typesafe.config.ConfigFactory
import config.GlobalConfig
import io.BufferedSource
import java.io.File
import play.api._
import libs.Files
import play.api.libs.json._
import com.codahale.jerkson;
import play.api.libs.concurrent.Akka;
import play.api.Play.current;

object Global extends GlobalSettings {

  var ioWorker: Option[IoWorker] = None;

  def getIoWorker: IoWorker = ioWorker match {
    case Some(ioWorker) => ioWorker
    case None => {
      // every spray-can HttpClient (and HttpServer) needs an IoWorker for low-level network IO
      // (but several servers and/or clients can share one)
      val newIoWorker = new IoWorker(Akka.system).start()
      ioWorker = Some(newIoWorker)
      newIoWorker
    }
  }

  override def onStart(app: Application) {
    Logger.info("Application has started")


    // create and start a spray-can HttpClient
    val sprayHttpClient = Akka.system.actorOf(
      props = Props(new HttpClient(getIoWorker)),
      name = "http-client"
    )

    val appConfig = ConfigFactory.load();
    val config = new GlobalConfig(ConfigFactory.load("my.conf").withFallback(appConfig))



    val buildMonitoringSupervisor = Akka.system.actorOf(Props(new BuildMonitoringSupervisor(sprayHttpClient, config)), name = "buildMonitoringSupervisor")


    val listener = Akka.system.actorOf(Props(new Actor {
      def receive = {
        case d: DeadLetter ⇒ println(d)
      }
    }))
    Akka.system.eventStream.subscribe(listener, classOf[DeadLetter])
  }

  override def onStop(app: Application) {
    ioWorker.map(_.stop())
    Logger.info("Application shutdown...")
  }

}