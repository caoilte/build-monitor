package actors

import akka.actor.{DeadLetter, Actor, Props, ActorSystem}
import cc.spray.can.client.HttpClient
import com.typesafe.config.ConfigFactory
import config.GlobalConfig
import cc.spray.io.IOBridge
import play.api.libs.concurrent.Akka
import akka.util.Timeout
import akka.dispatch.Await
import akka.util.duration._;
import akka.pattern.ask
import BuildMonitoringSupervisor._;

class BuildMonitor {

  val system = ActorSystem("Build-Monitor-System");

  // every spray-can HttpClient (and HttpServer) needs an IOBridge for low-level network IO
  // (but several servers and/or clients can share one)
  val ioBridge = new IOBridge(system).start();

  // create and start a spray-can HttpClient
  val sprayHttpClient = system.actorOf(
    props = Props(new HttpClient(ioBridge)),
    name = "spray-http-client"
  );

  val appConfig = ConfigFactory.load();
  val config = new GlobalConfig(ConfigFactory.load("my.conf").withFallback(appConfig))

  val buildMonitoringSupervisor = system.actorOf(
    Props(new BuildMonitoringSupervisor(sprayHttpClient, config)),
    name = "buildMonitoringSupervisor"
  );


  val listener = system.actorOf(Props(new Actor {
    def receive = {
      case d: DeadLetter ⇒ println(d)
    }
  }))
  system.eventStream.subscribe(listener, classOf[DeadLetter])


  def shutdown() = {
    implicit val timeout = Timeout(30 seconds);

    try {
      println(Await.result(buildMonitoringSupervisor ? ShutdownRequest, timeout.duration))
    } catch {
      case e ⇒ {
        println("This error occured: "+e.getMessage)
      }
    } finally {
      system.shutdown();
      system.awaitTermination();
      ioBridge.stop();
    }


  }

}
