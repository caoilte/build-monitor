package actors.jenkins

import akka.actor.{ActorLogging, Actor, ActorRef}
import config.{IJobConfig, JobConfig, JenkinsConfig}
import actors.jenkins.JenkinsMonitoring.JenkinsMonitor
import net.liftweb.json.JsonAST._
import akka.util.duration._
import actors.BuildStateActor._
import net.liftweb.json.JsonAST.JInt
import akka.event.LoggingAdapter
import collection.immutable.HashSet
import play.api.Logger
import actors.BuildStateActor.BuildSucceeded
import net.liftweb.json.JsonAST.JField
import net.liftweb.json.JsonAST.JBool
import net.liftweb.json.JsonAST.JString
import actors.BuildStateActor.BuildFailed
import scala.Some
import net.liftweb.json.JsonAST.JInt


class LatestBuildMonitor(jobConfig: IJobConfig) extends JenkinsMonitor[BuildStateMessage]
{
  val log = Logger("LatestBuildMonitor")


  val query = Query(jobConfig.name, "/lastBuild")
  val queryPeriod = 30 seconds

  def transformQueryResponse(json: JValue) = {
    if (isBuilding(json)) {
      None
    } else {
      transformCompletedBuild(json)
    }
  }

  private def isBuilding(json: JValue) = {
    json \ "building" match {
      case JBool(true) => true
      case JBool(false) => false
      case other => {
        log.warn("unexpected JSON '"+other+"' for building status. Will assume building");
        true
      }
    }
  }

  private def transformCompletedBuild(json: JValue) = {

    val buildNumber:Int = getBuildNumber(json)
    val committersSincePreviousGoodBuild = findCommittersSincePreviousGoodBuild(json)
    val triggeredManually = isTriggeredManually(json)
    val committersThisBuild = if (triggeredManually) {
      userWhoStartedManually(json)
    } else {
      findCommittersThisBuild(json)
    }

    val details = BuildDetails(triggeredManually, buildNumber, committersThisBuild, committersSincePreviousGoodBuild)
    if (!buildBroken(json)) {
      Some(BuildSucceeded(details))
    } else {
      Some(BuildFailed(details))
    }
  }

  private def isTriggeredManually(json: JValue) = {
    json \ "actions" \ "causes" \ "shortDescription" match {
      case JString("Started by an SCM change") => false
      case _ => true
    }
  }

  private def userWhoStartedManually(json: JValue) = {
    json \ "actions" \ "causes" \ "userName" match {
      case JString(userName) => new HashSet() + userName
      case _ => throw new Exception("No user found who started build manually")
    }
  }

  private def getBuildNumber(json: JValue) = {
    json \ "number" match {
      case JInt(buildNumber) => buildNumber toInt
      case _ => 0
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
//    changeSetAuthors match {
//      case JArray(committers: List[JValue]) => {
        changeSetAuthors.fold[HashSet[String]](HashSet[String]()) { (set:HashSet[String], jvalue:JValue) =>
          findFullNameOfCommitter(jvalue) match {
            case Some(fullName) => set + fullName
            case None => set
          }
        }
//      }
//      case _ => {
//        new HashSet[String]()
//      }
//    }
  }

  def findFullNameOfCommitter(json: JValue): Option[String] = {
    val fullNameJson = (json \ "author" \ "fullName")

    fullNameJson match {
      case JString(fullName: String) => Some(fullName)
      case _ => None
    }
  }
}