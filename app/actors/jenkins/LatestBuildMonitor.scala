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
  case class LatestBuild(isBuilding: Boolean, cause: Option[Cause], buildNumber: Int,
                          result: String, committersThisBuild: List[String],
                          committersSincePreviousGoodBuild: List[String],
                          timestamp: Long) {
    def triggerCause: String = cause match {
      case Some(cause) => cause.shortDescription
      case None => ""
    }
    def triggerUser: String = cause match {
      case Some(cause) => cause.userName.getOrElse("")
      case None => ""
    }
  }

  def trim(implicit r:Reads[String]): Reads[String] = r.map(_.trim)


  implicit val causeReads = (
    (__ \ "shortDescription").read[String](trim) ~
      (__ \ "userName").readOpt[String]
    )(Cause)

  implicit val causesReads = (
    (__)(0).read[Cause](causeReads)
    )

  def actionCauseReads: Reads[Cause] =
    ((__)(0) \ "causes").read[Cause](causesReads) or
      ((__)(1) \ "causes").read[Cause](causesReads)


  implicit val fullNameReads = (
    (__ \\ "fullName").read[String]
    )
  implicit val userNameReads = (
    (__ \\ "userName").read[String])


  implicit val latestBuildReads = (
    (__ \ "building").read[Boolean] ~
    (__ \ "actions").readOpt[Cause](actionCauseReads) ~
    (__ \ "number").read[Int] ~
    (__ \ "result").read[String] ~
    (__ \ "changeSet" \ "items").lazyRead(list[String](fullNameReads)) ~
    (__ \ "culprits").lazyRead(list[String](fullNameReads)) ~
    (__ \ "timestamp").read[Long]
  )(LatestBuild)
}


class LatestBuildMonitor(jobConfig: IJobConfig) extends JenkinsMonitor[BuildStateMessage] {
  import LatestBuildMonitor._

  val log = Logger("LatestBuildMonitor_"+jobConfig.underScoredName)


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

    val details = BuildDetails(latestBuild.timestamp, triggeredManually, latestBuild.buildNumber, triggerUsers,
      new HashSet() ++ latestBuild.committersSincePreviousGoodBuild)

    if (!latestBuild.result.equals("FAILURE")) {
      Some(BuildSucceeded(details))
    } else {
      Some(BuildFailed(details))
    }
  }
}