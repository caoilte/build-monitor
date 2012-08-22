package actors

import akka.actor.{Props, ActorLogging, ActorRef, Actor}
import config.{JobConfig, JenkinsConfig}
import net.liftweb.json.JsonAST._
import akka.util.duration._
import actors.BuildStatusMonitoringActor.{GenerateBuildStateMessage, Tick}
import akka.util.duration._
import akka.util.Timeout
import akka.pattern.ask
import actors.HttpClientActor.{JsonReply, JsonQuery}
import java.net.{URLEncoder, URI}
import actors.BuildStateActor.{BuildStateMessage, BuildFailed}
import collection.immutable.HashSet
import akka.dispatch.{Await, Future}
import actors.HttpClientActor.JsonReply
import config.JenkinsConfig
import scala.Some
import actors.HttpClientActor.JsonQuery
import net.liftweb.json.JsonAST.JField
import config.JobConfig
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JInt
import actors.BuildStateActor.BuildStateMessage
import com.typesafe.config.{ConfigFactory, Config}
;


object BuildStatusMonitoringActor {
  case object Tick
  case class GenerateBuildStateMessage(lastBuildJson: JValue, allBuildsJson: JValue)
}

class BuildStatusMonitoringActor(buildStateActor: ActorRef, httpClient: ActorRef, jenkinsConfig: JenkinsConfig, jobConfig: JobConfig)
  extends Actor with ActorLogging {

  private def jenkinsJsonApiUrl(apiName:Option[String]): String = {

    val path = "/job/" + jobConfig.name + apiName.getOrElse("") + "/api/json"
    new URI(
      "http",
      null,
      jenkinsConfig.url,
      jenkinsConfig.port,
      path,
      null,
      null).toASCIIString;
  }


  val lastBuildUrl = jenkinsJsonApiUrl(Some("/lastBuild"));
  val allBuildsUrl = jenkinsJsonApiUrl(None);




  override def preStart() = {

    context.system.scheduler.scheduleOnce(500 milliseconds, self, Tick);
  }


  def buildBroken(json: JValue): Boolean = {
    (json \ "result") match {
      case JString("SUCCESS") => false
      case JString("FAILURE") => true
      case _ => false
    }
  }

  def getCulprits(lastBuildJson: JValue): HashSet[String] = {
    val culpritsJson: JValue = (lastBuildJson \ "culprits")

    culpritsJson.fold[HashSet[String]](HashSet[String]()) { (set:HashSet[String], jvalue:JValue) =>
      jvalue match {
        case JField("fullName", JString(fullName: String)) => set + fullName
        case _ => set
      }
    }
  }

  def createBuildStateMessage(lastBuildJson: JValue, allBuildsJson: JValue): BuildStateMessage = {
    import BuildStateActor._

    val JString(jobName) = allBuildsJson \ "displayName"
    val culprits = getCulprits(lastBuildJson)
    val lastBuildNumber = allBuildsJson \ "lastBuild" \ "number" match {
      case JInt(lastBuildNumber) => lastBuildNumber toInt
      case JNothing => 0
    }


    if (!buildBroken(lastBuildJson)) {
      val lastBrokenBuildNumber:Int = allBuildsJson \ "lastFailedBuild" \ "number" match {
        case JInt(lastBrokenBuildNumber) => lastBrokenBuildNumber toInt
        case JNothing => 0
      }

      BuildSucceeded(jobName, lastBuildNumber - lastBrokenBuildNumber, culprits)

    } else {

      val lastSuccessfulBuildNumber = allBuildsJson \ "lastSuccessfulBuild" \ "number" match {
        case JInt(lastSuccessfulBuildNumber) => lastSuccessfulBuildNumber toInt
        case JNothing => 0
      }

      BuildFailed(jobName, lastBuildNumber - lastSuccessfulBuildNumber, culprits)
    }

  }

  protected def receive = {


    case Tick => {
      implicit val timeout = Timeout(60 seconds)

      val allBuildsFuture = ask(httpClient, JsonQuery(allBuildsUrl, jenkinsConfig.userName, jenkinsConfig.password))
      val lastBuildFuture = ask(httpClient, JsonQuery(lastBuildUrl, jenkinsConfig.userName, jenkinsConfig.password))

      for {
        lastBuildJson <- lastBuildFuture.mapTo[JsonReply]
        allBuildsJson <- allBuildsFuture.mapTo[JsonReply]
      } yield self ! GenerateBuildStateMessage(lastBuildJson.json, allBuildsJson.json)
    }
    case GenerateBuildStateMessage(lastBuildJson: JValue, allBuildsJson: JValue) => {
      val buildStateMsg = createBuildStateMessage(lastBuildJson, allBuildsJson);
      buildStateActor ! buildStateMsg

      context.system.scheduler.scheduleOnce(2 minutes, self, Tick);

    }

  }
}
