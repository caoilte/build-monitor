package actors

import akka.actor.{ActorRef, Props, Actor}
import config.GlobalConfig
import karotz.KarotzClient.{StartInteractiveMode}
import karotz.{KarotzClient}
import messaging.{MessageGeneratingActor, NameGeneratingActor}
import actors.BuildStateActor.SubscribeToStateDataChanges
import BuildMonitoringSupervisor._;


object BuildMonitoringSupervisor {
  case object ShutdownRequest;
  case object ShutdownComplete;
}


class BuildMonitoringSupervisor(sprayCanHttpClientActor: ActorRef, config: GlobalConfig) extends Actor{

  val funnel = context.actorOf(Props[PrioritisedMessageFunnel], "karotzMessageFunnel");

  val ledStateActor = context.actorOf(Props(new LedStateActor(funnel)), "ledStateActor");

  val karotzClient = context.actorOf(Props(new KarotzClient(funnel, ledStateActor, sprayCanHttpClientActor, config.karotzConfig)), "karotzClient")

  karotzClient ! StartInteractiveMode

  override def preStart() {

    val sprayConduit = context.actorOf(
      props = Props(new SprayHttpClientActor(sprayCanHttpClientActor, config.jenkinsConfig)),
      name = "http-client"
    )

    val namingActor = context.actorOf(Props(new NameGeneratingActor(config.karotzConfig)), "namingGenerator")

    val messageGeneratingActor = context.actorOf(Props(new MessageGeneratingActor(namingActor, funnel)), "messageGeneratingActor");

    for (jobConfig <- config.jobs) {

      val akkaJobName = jobConfig.name.replace(' ', '_');

      val buildStateActor = context.actorOf(Props[BuildStateActor],
        "buildState_for_'"+akkaJobName+"'");
      buildStateActor ! SubscribeToStateDataChanges(messageGeneratingActor);
      buildStateActor ! SubscribeToStateDataChanges(ledStateActor);

      context.actorOf(Props(new BuildStatusMonitoringActor(buildStateActor, sprayConduit, config.jenkinsConfig, jobConfig)),
        "buildStatusMonitor_for_'"+akkaJobName+"'");
    }
  }

  protected def receive = {
    case ShutdownRequest => karotzClient forward ShutdownRequest
  }
}
