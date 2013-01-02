package actors.jenkins

import akka.actor.{ActorLogging, Actor, ActorRef}
import config.{IJobConfig, JobConfig, JenkinsConfig}
import actors.jenkins.JenkinsMonitoring.JenkinsMonitor
import scala.concurrent.Await
import scala.concurrent.duration._
import actors.BuildStateActor._
import akka.event.LoggingAdapter
import collection.immutable.HashSet
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.Logger
import actors.BuildStateActor.BuildSucceeded
import actors.BuildStateActor.BuildFailed
import scala.Some

object LatestBuildMonitor {
  case class Cause(shortDescription: String, userName: Option[String])
  case class FullName(fullName: String)
  case class LatestBuild(isBuilding: Boolean, causes: List[Cause], buildNumber: Int,
                          result: String, committersThisBuild: List[String],
                          committersSincePreviousGoodBuild: List[String]) {
    def triggerCause: String = causes(0).shortDescription
    def triggerUser: String = causes(0).userName.getOrElse("")
  }


  implicit val causeReads = (
    (__ \ "shortDescription").read[String] ~
      (__ \ "userName").readOpt[String]
    )(Cause)

  implicit val fullNameReads = (
    (__ \\ "fullName").read[String]
    )
  implicit val userNameReads = (
    (__ \\ "userName").read[String])


  implicit val latestBuildReads = (
    (__ \ "building").read[Boolean] ~
    ((__ \ "actions")(0) \ "causes").lazyRead(list[Cause](causeReads)) ~
      (__ \ "number").read[Int] ~
      (__ \ "result").read[String] ~
      (__ \ "changeSet" \ "items").lazyRead(list[String](fullNameReads)) ~
      (__ \ "culprits").lazyRead(list[String](fullNameReads))
    )(LatestBuild)
}


class LatestBuildMonitor(jobConfig: IJobConfig) extends JenkinsMonitor[BuildStateMessage] {
  import LatestBuildMonitor._

  val log = Logger("LatestBuildMonitor")


  val query = Query(jobConfig.name, "/lastBuild")
  val queryPeriod = 30 seconds

  def transformQueryResponse(json: JsValue) = {
    json.validate[LatestBuild].fold(
      valid = { res => {
        if (res.isBuilding) {
          None
        } else {
          transformCompletedBuild(res)
        }
      } },
      invalid = { errors => {
        log.error("An error occurred de-serializing LatestBuild JSON from jenkins '"+errors+"'")
        None
      } }
    )
  }

  private def transformCompletedBuild(latestBuild: LatestBuild) = {

    val triggeredManually = !(latestBuild.triggerCause.equals("Started by an SCM change")
      || latestBuild.triggerCause.startsWith("Started by upstream project"))
    val triggerUsers = if (!triggeredManually) {
      new HashSet() ++ latestBuild.committersThisBuild
    } else {
      new HashSet() + latestBuild.triggerUser
    }

    val details = BuildDetails(triggeredManually, latestBuild.buildNumber, triggerUsers, new HashSet() ++ latestBuild.committersSincePreviousGoodBuild)

    if (!latestBuild.result.equals("FAILURE")) {
      Some(BuildSucceeded(details))
    } else {
      Some(BuildFailed(details))
    }
  }
}