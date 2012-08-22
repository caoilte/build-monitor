package actors

import akka.actor.{ActorRef, Props, Actor}
import config.GlobalConfig
import karotz.KarotzClientAdaptor
import messaging.{MessageGeneratingActor, NameGeneratingActor}
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.routing.Listen
import actors.BuildStateActor.SubscribeToStateDataChanges
import cc.spray.io.IoWorker
import cc.spray.can.client.HttpClient
import play.api.libs.concurrent.Akka


class BuildMonitoringSupervisor(sprayCanHttpClientActor: ActorRef, config: GlobalConfig) extends Actor {

  override def preStart() {
    val funnel = context.actorOf(Props[PrioritisedMessageFunnel], "karotzMessageFunnel");
    val dispatchHttpClientActor = context.actorOf(Props(new HttpClientActor(config.httpConfig, config.jenkinsConfig)), "httpClientAdaptor");

    val sprayConduit = context.actorOf(
      props = Props(new SprayHttpClientActor(sprayCanHttpClientActor, config.httpConfig, config.jenkinsConfig)),
      name = "http-client"
    )

    val namingActor = context.actorOf(Props(new NameGeneratingActor(config.karotzConfig)), "namingGenerator")

    val messageGeneratingActor = context.actorOf(Props(new MessageGeneratingActor(namingActor, funnel)), "messageGeneratingActor");
    val ledStateActor = context.actorOf(Props(new LedStateActor(funnel)), "ledStateActor");

    for (jobConfig <- config.jobs) {

      val akkaJobName = jobConfig.name.replace(' ', '_');

      val buildStateActor = context.actorOf(Props[BuildStateActor],
        "buildState_for_'"+akkaJobName+"'");
      buildStateActor ! SubscribeToStateDataChanges(messageGeneratingActor);
      buildStateActor ! SubscribeToStateDataChanges(ledStateActor);

      context.actorOf(Props(new BuildStatusMonitoringActor(buildStateActor, dispatchHttpClientActor, config.jenkinsConfig, jobConfig)),
        "buildStatusMonitor_for_'"+akkaJobName+"'");
    }

    val karotzClientAdaptor = context.actorOf(Props(new KarotzClientAdaptor(funnel, config.karotzConfig)), "karotzClientAdaptor");


  }

  protected def receive = null
}
