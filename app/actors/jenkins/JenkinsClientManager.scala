package actors.jenkins

import akka.actor._
import config.JenkinsConfig
import cc.spray.client.HttpConduit
import akka.util.Timeout
import cc.spray.client.HttpConduit._
import cc.spray.http.{HttpResponse, BasicHttpCredentials}
import java.net.URI
import akka.event.LoggingReceive
import akka.dispatch.Future
import akka.pattern.AskTimeoutException
import akka.actor.FSM.Failure
import java.lang.String
import scala.Predef._
import net.liftweb.json._
import cc.spray.http.HttpResponse
import akka.actor.FSM.Failure
import actors.jenkins.JenkinsClientManager._
import akka.actor.FSM.Failure
import cc.spray.http.HttpResponse
import net.liftweb.json.JsonAST.JValue
import akka.util.duration._
import akka.util.{Deadline, Timeout}
import actors.ExponentialBackOff
import akka.actor.FSM.Failure
import cc.spray.http.HttpResponse
import actors.ExponentialBackOff
import collection.immutable.HashSet
import akka.actor.FSM.Failure
import scala.Some
import cc.spray.http.HttpResponse
import actors.ExponentialBackOff
import actors.jenkins.JenkinsClientManager.JsonReply
import actors.jenkins.JenkinsClientManager.TrackedQuery
import akka.actor.FSM.Failure
import scala.Some
import cc.spray.http.HttpResponse
import actors.ExponentialBackOff
import actors.jenkins.JenkinsClientManager.JsonQuery
import actors.jenkins.JenkinsClientManager.DoJsonReply

object JenkinsClientManager {
  case class TrackedQuery(originalSender:ActorRef, query: String)

  sealed trait State
  case object ClosedCircuit extends State
  case object HalfOpenCircuit extends State
  case object OpenCircuit extends State
  sealed trait Data
  case class ClosedCircuitData(backOff: ExponentialBackOff) extends Data
  case class OpenCircuitData(scheduleResult: Option[Cancellable], backOff: ExponentialBackOff, blockedQueries: List[TrackedQuery]) extends Data {
    def addQuery(trackedQuery: TrackedQuery): OpenCircuitData = this.copy(blockedQueries = this.blockedQueries :+ trackedQuery)
  }


  abstract class HttpClientActorMessage
  case class JsonQuery(query: String) extends HttpClientActorMessage
  case class JsonReply(json: JValue) extends HttpClientActorMessage

  case class DoJsonReply(originalSender: ActorRef, response: HttpResponse) extends HttpClientActorMessage
  case class JsonQueryFailed(originalSender: ActorRef, query: String) extends HttpClientActorMessage
  case object Tick extends HttpClientActorMessage

}

class JenkinsClientManager(httpClient: ActorRef, jenkinsConfig: JenkinsConfig) extends Actor with FSM[State, Data] {

  startWith(ClosedCircuit, ClosedCircuitData(ExponentialBackOff(2 seconds, 12, true)))


  import actors.jenkins.JenkinsClientManager._

  val conduit = context.system.actorOf(
    props = Props(new HttpConduit(httpClient, jenkinsConfig.url, jenkinsConfig.port)),
    name = "http-conduit"
  )

  implicit val timeout = Timeout(30 seconds);


  import HttpConduit._
  val pipeline = addCredentials(BasicHttpCredentials(jenkinsConfig.userName, jenkinsConfig.password)) ~> sendReceive(conduit)


  when(ClosedCircuit) {
    case Event(JsonQuery(query), data) => {
      doQuery(sender, query)

      stay()
    }
    case Event(DoJsonReply(originalSender, response), data) => {
      val json = new String(response.entity.asString)

      originalSender ! JsonReply(parse(json))
      stay()
    }
    case Event(JsonQueryFailed(originalSender, query), ClosedCircuitData(backOff)) => {
      val newBackOff = backOff.nextBackOff

      log.error("Jenkins Client Failed. This is failure number {}. Scheduling restart in {}", newBackOff.retries, newBackOff.waitTime)
      val scheduleResult = Some(context.system.scheduler.scheduleOnce(backOff.waitTime, self, Tick))

      goto(OpenCircuit) using OpenCircuitData(scheduleResult, newBackOff, List(TrackedQuery(originalSender, query)))
    }
  }

  when(OpenCircuit) {
    case Event(JsonQuery(query), openCircuitData: OpenCircuitData) => {
      stay() using openCircuitData.addQuery(TrackedQuery(sender, query))
    }
    case Event(JsonQueryFailed(originalSender, query), openCircuitData: OpenCircuitData) => {
      stay() using openCircuitData.addQuery(TrackedQuery(originalSender, query))
    }
    case Event(Tick, openCircuitData: OpenCircuitData) => {

      openCircuitData.blockedQueries match {
        case head :: tail => {
          doQuery(head.originalSender, head.query)
          goto(HalfOpenCircuit) using openCircuitData.copy(blockedQueries = tail)
        }
        case Nil => goto(HalfOpenCircuit)
      }
    }
  }

  when(HalfOpenCircuit) {
    case Event(DoJsonReply(originalSender, response), openCircuitData: OpenCircuitData) => {
      val json = new String(response.entity.asString)

      originalSender ! JsonReply(parse(json))

      log.info("Succeeded in sending single request to Jenkins Client while in half open state. Will now fully close circuit and resend all previously failed requests")
      openCircuitData.blockedQueries.foreach(trackedQuery => doQuery(trackedQuery.originalSender, trackedQuery.query))
      goto(ClosedCircuit) using ClosedCircuitData(openCircuitData.backOff.reset())
    }
    case Event(JsonQueryFailed(originalSender, query), openCircuitData: OpenCircuitData) => {
      val newBackOff = openCircuitData.backOff.nextBackOff
      log.error("Jenkins Client Failed. This is failure number {}. Circuit has been opened. Will attempt to half close in {}", newBackOff.retries, newBackOff.waitTime)
      val scheduleResult = Some(context.system.scheduler.scheduleOnce(openCircuitData.backOff.waitTime, self, Tick))

      goto(OpenCircuit) using OpenCircuitData(scheduleResult, newBackOff, openCircuitData.blockedQueries :+ TrackedQuery(originalSender, query))
    }
  }

  def getURI(path: String): URI = { new URI(
    "http",
    null,
    jenkinsConfig.url,
    jenkinsConfig.port,
    path,
    null,
    null);
  }
  val baseUrl = getURI("")
  private def getPathFromUrl(urlString: String):String = {
    "/" + baseUrl.relativize(new URI(urlString))
  }

  private def doQuery(originalSender: ActorRef, query: String) {
    val finalUrl = getPathFromUrl(query)

    log.debug("Query is ["+finalUrl+"]")

    val responseF: Future[HttpResponse] = pipeline(Get(finalUrl))

    responseF.onSuccess {
      case response => {
        log.debug("Reply for query [{}] received", finalUrl);
        self ! DoJsonReply(originalSender, response)
      };
    }
    responseF.onFailure {
      case e:AskTimeoutException => {
        log.error("Jenkins Query [{}] timed out after [{}]", finalUrl, timeout)
        self ! JsonQueryFailed(originalSender, query)
      }
      case e:Exception => {
        //log.error(e, "Unknown Exception thrown by query [{}]", finalUrl)
        //e.printStackTrace();
        self ! JsonQueryFailed(originalSender, query)
        //self ! JsonQuery(urlString, username, password)
      };
    }
  }

}
