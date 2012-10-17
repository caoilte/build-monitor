package actors

import akka.actor._
import config.{JobConfig, JenkinsConfig}
import net.liftweb.json.JsonAST._
import akka.util.duration._
import actors.BuildStatusMonitoringActor.{RegisterStatusMonitoring, GenerateBuildStateMessage}
import akka.util.duration._
import akka.util.Timeout
import akka.pattern.{AskTimeoutException, ask}
import java.net.{URLEncoder, URI}
import actors.BuildStateActor._
import collection.immutable.HashSet
import akka.dispatch.{Await, Future}
import config.JenkinsConfig
import scala.Some
import net.liftweb.json.JsonAST.JField
import config.JobConfig
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JInt
import com.typesafe.config.{ConfigFactory, Config}
import actors.SprayHttpClientActor.{JsonQuery, JsonReply}
import actors.SprayHttpClientActor.JsonReply
import actors.BuildStatusMonitoringActor._
import scala.Some
import actors.SprayHttpClientActor.JsonQuery
import net.liftweb.json.JsonAST.JField
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JInt
import actors.SprayHttpClientActor.JsonReply
import actors.BuildStatusMonitoringActor.GenerateBuildStateMessage
import actors.BuildStatusMonitoringActor.HandleBuildInformationResponse
import actors.BuildStatusMonitoringActor.RegisterStatusMonitoring
import actors.BuildStatusMonitoringActor.Data
import scala.Some
import actors.BuildStatusMonitoringActor.State
import actors.SprayHttpClientActor.JsonQuery
import net.liftweb.json.JsonAST.JField
import actors.BuildStatusMonitoringActor.Listener
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JInt
import actors.BuildStateActor.BuildFailed
;


object BuildStatusMonitoringActor {
  case object QueryBuildInformation
  case class HandleBuildInformationResponse(json: JValue)
  case object QueryLatestBuild
  case class HandleLatestBuildResponse(json: JValue)
  case class RegisterStatusMonitoring(actorRef: ActorRef)
  case class GenerateBuildStateMessage(lastBuildJson: JValue, allBuildsJson: JValue)


  sealed trait State;
  case object Idle extends State;
  case object Initialising extends State;
  case object Active extends State;

  sealed trait Data;
  case object Uninitialised extends Data;
  case class Listener(listener: ActorRef) extends Data;

}

class BuildStatusMonitoringActor(httpClient: ActorRef, jenkinsConfig: JenkinsConfig, jobConfig: JobConfig)
  extends Actor with FSM[State, Data] {

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


  startWith(Idle, Uninitialised)

  when(Idle) {
    case Event(RegisterStatusMonitoring(listener), _) => {
      self ! QueryBuildInformation
      goto(Initialising) using Listener(listener)
    }
  }

  when(Initialising) {
    case Event(QueryBuildInformation, _) => {
      queryBuildInformation
      stay()
    }
    case Event(HandleBuildInformationResponse(json), Listener(listener)) => {
      handleBuildInformationResponse(json, listener)
      self ! QueryLatestBuild
      goto(Active)
    }

  }

  when(Active) {
    case Event(QueryBuildInformation, _) => {
      queryBuildInformation
      stay()
    }
    case Event(HandleBuildInformationResponse(json), Listener(listener)) => {
      handleBuildInformationResponse(json, listener)
      stay()
    }
    case Event(QueryLatestBuild, _) => {

      implicit val timeout = Timeout(60 seconds)
      val lastBuildFuture = ask(httpClient, JsonQuery(lastBuildUrl, jenkinsConfig.userName, jenkinsConfig.password))

      val resultMessageFuture: Future[HandleLatestBuildResponse] = for {
        lastBuildJson <- lastBuildFuture.mapTo[JsonReply]
      } yield HandleLatestBuildResponse(lastBuildJson.json);


      val originalSender = sender
      resultMessageFuture.onSuccess {
        case message => {
          self ! message
        };
      }
      resultMessageFuture.onFailure {
        case e:AskTimeoutException => {
          log.error("Build Status Queries timed out after {}. Will schedule another check in 15 seconds.", timeout);

          if (context != null) {
            context.system.scheduler.scheduleOnce(15 seconds, self, QueryLatestBuild);
          }
        }
        case e:Exception => {
          e.printStackTrace()
        };
      }
      stay()
    }
    case Event(HandleLatestBuildResponse(json), Listener(listener)) => {

      val isBuilding:Boolean = json \ "building" match {
        case JBool(true) => true
        case JBool(false) => false
        case other => {
          log.warning("unexpected JSON '{}' for building status. Will assume building", other);
          true
        }
      }

      if (isBuilding) {
        context.system.scheduler.scheduleOnce(15 seconds, self, QueryLatestBuild);
        stay()
      } else {
        val buildNumber:Int = json \ "number" match {
          case JInt(buildNumber) => buildNumber toInt
          case JNothing => 0
        }
        val committersSincePreviousGoodBuild = findCommittersSincePreviousGoodBuild(json)
        val committersThisBuild = findCommittersThisBuild(json)

        if (!buildBroken(json)) {
          listener ! BuildSucceeded(buildNumber, committersThisBuild, committersSincePreviousGoodBuild)
        } else {
          listener ! BuildFailed(buildNumber, committersThisBuild, committersSincePreviousGoodBuild)

        }

        context.system.scheduler.scheduleOnce(30 seconds, self, QueryLatestBuild);
        stay()
      }
    }

  }

  def queryBuildInformation {
    implicit val timeout = Timeout(60 seconds)

    val allBuildsFuture = ask(httpClient, JsonQuery(allBuildsUrl, jenkinsConfig.userName, jenkinsConfig.password))

    val resultMessageFuture: Future[HandleBuildInformationResponse] = for {
      allBuildsJson <- allBuildsFuture.mapTo[JsonReply]
    } yield HandleBuildInformationResponse(allBuildsJson.json);

    resultMessageFuture.onSuccess {
      case message => {
        self ! message
      };
    }
    resultMessageFuture.onFailure {
      case e:AskTimeoutException => {
        log.error("Build Status Queries timed out after {}. Will schedule another check in 15 seconds.", timeout);

        if (context != null) {
          context.system.scheduler.scheduleOnce(15 seconds, self, QueryBuildInformation);
        }
      }
      case e:Exception => {
        e.printStackTrace()
      };
    }
  }
  def handleBuildInformationResponse(json: JValue, listener: ActorRef) {
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

    listener ! BuildInformation(jobName, lastBuildNumber, lastSuccessfulBuildNumber, lastFailedBuildNumber)

    context.system.scheduler.scheduleOnce(15 minutes, self, QueryBuildInformation);
  }


  def buildBroken(json: JValue): Boolean = {
    (json \ "result") match {
      case JString("SUCCESS") => false
      case JString("FAILURE") => true
      case other => {
        log.warning("Unexpected JSON '{}' will assume build passed", other);
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
