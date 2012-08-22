package actors

import akka.actor.{ActorRef, ActorLogging, Actor}
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json._
import akka.event.LoggingReceive
import config.{JenkinsConfig, HttpConfig}
import akka.util.duration._
import com.ning.http.client.RequestBuilder
import cc.spray.client.{ConduitSettings, DispatchStrategies, Get, HttpConduit}
import cc.spray.http._
import akka.dispatch.Future
import cc.spray.http.HttpRequest
import config.JenkinsConfig
import config.HttpConfig
import com.typesafe.config.ConfigFactory
import actors.SprayHttpClientActor._
import java.net.URI
import actors.HttpClientActor.{JsonReply, JsonQuery, HttpClientActorMessage}


object SprayHttpClientActor {

  case class DoJsonReply(originalSender: ActorRef, response: HttpResponse) extends HttpClientActorMessage

}

class SprayHttpClientActor(httpClient: ActorRef, httpConfig: HttpConfig, jenkinsConfig: JenkinsConfig) extends Actor with ActorLogging {

  class MyConduit extends HttpConduit(httpClient, "dev-sandbox-dev00.development.playfish.com", 8080,
    DispatchStrategies.NonPipelined())(context.system) {
    val pipeline = simpleRequest ~> authenticate(BasicHttpCredentials(jenkinsConfig.userName, jenkinsConfig.password)) ~> sendReceive
  }

  var conduit: Option[MyConduit] = None;

  def getConduit:MyConduit = {
    conduit match {
      case Some(conduit) => conduit
      case None => {
        val newConduit = new MyConduit
        conduit = Some(newConduit)
        newConduit
      }
    }
  }

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

      log.info("Query is ["+finalUrl+"]")

      val responseF: Future[HttpResponse] = getConduit.pipeline(Get(finalUrl))


      val originalSender = sender
      for (response <- responseF) yield self ! DoJsonReply(originalSender, response)
    }
    case DoJsonReply(originalSender, response) => {
      response.content map {
        content => {
          val json = new String(content.buffer)

          originalSender ! JsonReply(parse(json));
        }
      }
    }
  }
}
