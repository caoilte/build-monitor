package actors

import akka.actor.{ActorRef, Props, Actor}
import config.GlobalConfig
import jenkins.JenkinsMonitoring.RegisterMonitoringListener
import jenkins.{LatestBuildMonitor, BuildInformationMonitor, JenkinsMonitoring, JenkinsClientManager}
import karotz.KarotzClientManager.PerformAction
import karotz.{KarotzClientManager, KarotzThroughputManager}
import messaging.{MessageGeneratingActor, NameGeneratingActor}
import actors.BuildStateActor.SubscribeToStateDataChanges
import actors.BuildMonitoringSupervisor.ShutdownRequest


object BuildMonitoringSupervisor {
  case object ShutdownRequest
}


class BuildMonitoringSupervisor(sprayCanHttpClientActor: ActorRef, config: GlobalConfig) extends Actor{

  val funnel = context.actorOf(Props[PrioritisedMessageFunnel], "karotzMessageFunnel");

  val ledStateActor = context.actorOf(Props(new LedStateActor(funnel)), "ledStateActor");

  val karotzClientManagerProps = Props(new KarotzClientManager(config.karotzConfig))

  val karotzThroughputManager = context.actorOf(Props(
    new KarotzThroughputManager(karotzClientManagerProps, funnel, ledStateActor)), "karotz-throughput-manager")

  val jenkinsClientManager = context.actorOf(
    props = Props(new JenkinsClientManager(sprayCanHttpClientActor, config.jenkinsConfig)),
    name = "jenkins-client"
  )

  override def preStart() {


    val namingActor = context.actorOf(Props(new NameGeneratingActor(config.karotzConfig)), "namingGenerator")

    val messageGeneratingActor = context.actorOf(Props(new MessageGeneratingActor(namingActor, funnel)), "messageGeneratingActor");

    for (jobConfig <- config.jobs) {

      val akkaJobName = jobConfig.underScoredName


      val buildInformationMonitor = context.actorOf(Props(new JenkinsMonitoring(jenkinsClientManager, new BuildInformationMonitor(jobConfig))),
        "buildInformationMonitor_for_'"+akkaJobName+"'");
      val latestBuildMonitor = context.actorOf(Props(new JenkinsMonitoring(jenkinsClientManager, new LatestBuildMonitor(jobConfig))),
        "latestBuildMonitor_for_'"+akkaJobName+"'");

      val buildStateActor = context.actorOf(Props(new BuildStateActor()),
        "buildState_for_'"+akkaJobName+"'");
      buildInformationMonitor ! RegisterMonitoringListener(buildStateActor)
      latestBuildMonitor ! RegisterMonitoringListener(buildStateActor)
      buildStateActor ! SubscribeToStateDataChanges(messageGeneratingActor);
      buildStateActor ! SubscribeToStateDataChanges(ledStateActor);

    }
  }

  override def receive = {
    case ShutdownRequest => karotzThroughputManager forward ShutdownRequest
  }
}
