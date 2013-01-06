package actors.jenkins

import akka.actor.{ActorLogging, Actor, ActorRef}
import config.{JobConfig, JenkinsConfig}
import actors.jenkins.JenkinsMonitoring.JenkinsMonitor
//import net.liftweb.json.JsonAST.{JNothing, JInt, JString, JValue}

import scala.concurrent.Await
import scala.concurrent.duration._
import actors.BuildStateActor.{BuildStateMessage, BuildInformation}
import akka.event.LoggingAdapter
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.Logger

object BuildInformationMonitor {
  implicit val buildInformationReads = (
    (__ \ "description").read[String] ~
    (__ \ "lastBuild" \ "number").read[Int] ~
    (__ \ "lastSuccessfulBuild" \ "number").read[Int] ~
    (__ \ "lastFailedBuild" \ "number").readOpt[Int]
    )(BuildInformation)
}

class BuildInformationMonitor(jobConfig: JobConfig) extends JenkinsMonitor[BuildStateMessage] {
  import BuildInformationMonitor._

  val log = Logger("BuildInformationMonitor_"+jobConfig.underScoredName)



  val query = Query(jobConfig.name)
  val queryPeriod = 15 minutes

  def transformQueryResponse(json: JsValue) = {
    json.validate[BuildInformation].fold(
      valid = { res => Some(res) },
      invalid = { errors => {
        log.error("An error occurred de-serializing BuildInformation JSON from jenkins '"+errors+"'")
        None
      } }
    )
  }
}
