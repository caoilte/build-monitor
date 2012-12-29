package actors.jenkins

import akka.actor.{ActorLogging, Actor, ActorRef}
import config.{JobConfig, JenkinsConfig}
import actors.jenkins.Query
import actors.jenkins.JenkinsMonitoring.JenkinsMonitor
import net.liftweb.json.JsonAST.{JNothing, JInt, JString, JValue}
import akka.util.duration._
import actors.BuildStateActor.{BuildStateMessage, BuildInformation}
import akka.event.LoggingAdapter


class BuildInformationMonitor(jobConfig: JobConfig) extends JenkinsMonitor[BuildStateMessage]
{
  val query = Query(jobConfig.name)
  val queryPeriod = 15 minutes

  def transformQueryResponse(json: JValue) = {
    val JString(jobName) = json \ "description"
    val lastBuildNumber = json \ "lastBuild" \ "number" match {
      case JInt(lastBuildNumber) => lastBuildNumber toInt
      case JNothing => 0
    }
    val lastFailedBuildNumber:Int = json \ "lastFailedBuild" \ "number" match {
      case JInt(lastBrokenBuildNumber) => lastBrokenBuildNumber toInt
      case JNothing => 0
    }
    val lastSuccessfulBuildNumber = json \ "lastSuccessfulBuild" \ "number" match {
      case JInt(lastSuccessfulBuildNumber) => lastSuccessfulBuildNumber toInt
      case JNothing => 0
    }

    Some(BuildInformation(jobName, lastBuildNumber, lastSuccessfulBuildNumber, lastFailedBuildNumber))
  }
}
