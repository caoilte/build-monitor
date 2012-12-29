package actors.jenkins

import akka.actor.{ActorLogging, Actor, ActorRef}
import config.{JobConfig, JenkinsConfig}
import actors.jenkins.Query
import actors.jenkins.JenkinsMonitoring.JenkinsMonitor
import net.liftweb.json.JsonAST._
import akka.util.duration._
import actors.BuildStateActor.{BuildStateMessage, BuildFailed, BuildSucceeded, BuildInformation}
import net.liftweb.json.JsonAST.JInt
import akka.event.LoggingAdapter
import collection.immutable.HashSet
import play.api.Logger


class LatestBuildMonitor(jobConfig: JobConfig) extends JenkinsMonitor[BuildStateMessage]
{
  val log = Logger("LatestBuildMonitor")


  val query = Query(jobConfig.name, "/lastBuild")
  val queryPeriod = 30 seconds

  def transformQueryResponse(json: JValue) = {
    val isBuilding:Boolean = json \ "building" match {
      case JBool(true) => true
      case JBool(false) => false
      case other => {
        log.warn("unexpected JSON '"+other+"' for building status. Will assume building");
        true
      }
    }

    if (isBuilding) {
      None
    } else {
      val buildNumber:Int = json \ "number" match {
        case JInt(buildNumber) => buildNumber toInt
        case JNothing => 0
      }
      val committersSincePreviousGoodBuild = findCommittersSincePreviousGoodBuild(json)
      val committersThisBuild = findCommittersThisBuild(json)

      if (!buildBroken(json)) {
        Some(BuildSucceeded(buildNumber, committersThisBuild, committersSincePreviousGoodBuild))
      } else {
        Some(BuildFailed(buildNumber, committersThisBuild, committersSincePreviousGoodBuild))
      }
    }
  }


  def buildBroken(json: JValue): Boolean = {
    (json \ "result") match {
      case JString("SUCCESS") => false
      case JString("FAILURE") => true
      case other => {
        log.warn("Unexpected JSON '"+other+"' will assume build passed");
        false
      }
    }
  }

  def findCommittersSincePreviousGoodBuild(lastBuildJson: JValue): HashSet[String] = {
    val culpritsJson: JValue = (lastBuildJson \ "culprits")

    culpritsJson.fold[HashSet[String]](HashSet[String]()) { (set:HashSet[String], jvalue:JValue) =>
      jvalue match {
        case JField("fullName", JString(fullName: String)) => set + fullName
        case _ => set
      }
    }
  }
  def findCommittersThisBuild(json: JValue): HashSet[String] = {
    val changeSetAuthors: JValue = (json \ "changeSet" \ "items")
    changeSetAuthors match {
      case JArray(committers: List[JValue]) => {
        changeSetAuthors.fold[HashSet[String]](HashSet[String]()) { (set:HashSet[String], jvalue:JValue) =>
          findFullNameOfCommitter(jvalue) match {
            case Some(fullName) => set + fullName
            case None => set
          }
        }
      }
      case _ => {
        new HashSet[String]()
      }
    }
  }

  def findFullNameOfCommitter(json: JValue): Option[String] = {
    val fullNameJson = (json \ "author" \ "fullName")

    fullNameJson match {
      case JString(fullName: String) => Some(fullName)
      case _ => None
    }
  }
}