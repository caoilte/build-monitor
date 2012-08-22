package actors

import akka.actor.{ActorRef, ActorLogging, Actor}
import actors.HttpClientActor.{JsonQueryFrom, DoJsonReply, JsonReply, JsonQuery}
import net.liftweb.json.JsonAST.JValue
import dispatch._
import net.liftweb.json._
import akka.event.LoggingReceive
import config.{JenkinsConfig, HttpConfig}
import akka.util.duration._
import com.ning.http.client.RequestBuilder
import akka.dispatch.Future
import actors.HttpClientActor.JsonReply
import config.JenkinsConfig
import actors.HttpClientActor.JsonQuery
import actors.HttpClientActor.DoJsonReply
import actors.HttpClientActor.JsonQueryFrom
import config.HttpConfig
import com.typesafe.config.{ConfigFactory, Config}


object HttpClientActor {

  abstract class HttpClientActorMessage
  case class JsonQueryFrom(from: ActorRef, query: JsonQuery) extends HttpClientActorMessage
  case class JsonQuery(query: String, username: String, password: String) extends HttpClientActorMessage
  case class DoJsonReply(originalSender: ActorRef, response: String) extends HttpClientActorMessage
  case class JsonReply(json: JValue) extends HttpClientActorMessage

}

class HttpClientActor(httpConfig: HttpConfig, jenkinsConfig: JenkinsConfig) extends Actor with ActorLogging {
  var h:Http = null;

  var openConnections = 0;

  override def preStart() = {
    log.debug("Starting HTTP Client");
    h = new Http
  }


  override def postStop() = {
    log.debug("Shutting down HTTP Client");
    h.shutdown()
  }

  private def handleJsonQuery(from: ActorRef, urlString: String, username: String, password:String) = {

    if (openConnections < httpConfig.maxOutgoingConnections) {
      openConnections += 1;

      log.info("Query is ["+urlString+"]")


      val query: RequestBuilder = dispatch.url(urlString).as_!(username, password)

      val replyPromise:Promise[String] = h(query OK as.String)
      for (reply <- replyPromise) yield self ! DoJsonReply(from, reply.toString)

    } else {
      log.debug("Delaying query")

      context.system.scheduler.scheduleOnce(2 seconds, self, JsonQueryFrom(from, JsonQuery(urlString, username, password)));
    }

  }

  protected def receive = LoggingReceive {
    case JsonQuery(urlString, username, password) => {
      handleJsonQuery(sender, urlString, username, password)
    }
    case JsonQueryFrom(from, jsonQuery) => {
      handleJsonQuery(from, jsonQuery.query, jsonQuery.username, jsonQuery.password)
    }
    case DoJsonReply(originalSender, response) => {
      openConnections -= 1;
      originalSender ! JsonReply(parse(response));
    }
  }
}
