package actors

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json._
import akka.event.LoggingReceive
import config.{JenkinsConfig}
import cc.spray.client.{ConduitSettings, DispatchStrategies, HttpConduit}
import cc.spray.http._
import akka.dispatch.Future
import config.JenkinsConfig
import actors.SprayHttpClientActor._
import java.net.URI
import cc.spray.http.HttpResponse
import actors.SprayHttpClientActor.DoJsonReply
import akka.pattern.AskTimeoutException
import akka.util.duration._
import akka.util.{Deadline, Timeout}
import akka.actor.FSM.Failure


object SprayHttpClientActor {


  abstract class HttpClientActorMessage
  case class JsonQuery(query: String, username: String, password: String) extends HttpClientActorMessage
  case class JsonReply(json: JValue) extends HttpClientActorMessage

  case class DoJsonReply(originalSender: ActorRef, response: HttpResponse) extends HttpClientActorMessage

}

class SprayHttpClientActor(httpClient: ActorRef, jenkinsConfig: JenkinsConfig) extends Actor with ActorLogging {


  val conduit = context.system.actorOf(
    props = Props(new HttpConduit(httpClient, jenkinsConfig.url, jenkinsConfig.port)),
    name = "jenkins-conduit"
  )

  implicit val timeout = Timeout(30 seconds);


  import HttpConduit._
  val pipeline = addCredentials(BasicHttpCredentials(jenkinsConfig.userName, jenkinsConfig.password)) ~> sendReceive(conduit)


  override def preStart() = {
    log.debug("Starting HTTP Client");
  }


  override def postStop() = {
    log.debug("Shutting down HTTP Client");
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

  protected def receive = LoggingReceive {
    case JsonQuery(urlString, username, password) => {
      val finalUrl = getPathFromUrl(urlString)

      log.debug("Query is ["+finalUrl+"]")

      val responseF: Future[HttpResponse] = pipeline(Get(finalUrl))


      val originalSender = sender

      responseF.onSuccess {
        case response => {
          log.debug("Reply for query [{}] received", finalUrl);
          self ! DoJsonReply(originalSender, response)
        };
      }
      responseF.onFailure {
        case e:AskTimeoutException => {
          log.error("Jenkins Query [{}] timed out after [{}]", finalUrl, timeout)
          originalSender ! Failure(e)
        }
        case e:Exception => {
          //log.error(e, "Unknown Exception thrown by query [{}]", finalUrl)
          //e.printStackTrace();
          originalSender ! Failure(e)
          //self ! JsonQuery(urlString, username, password)
        };
      }
    }
    case DoJsonReply(originalSender, response) => {
      val json = new String(response.entity.asString)

      originalSender ! JsonReply(parse(json));
    }
  }
}
