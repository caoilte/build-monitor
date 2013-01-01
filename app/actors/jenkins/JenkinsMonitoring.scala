package actors.jenkins

import akka.actor.{ActorRef, ActorLogging, Actor}
import actors.jenkins.JenkinsMonitoring._
import actors.jenkins.JenkinsClientManager.{JsonReply, JsonQuery}
import actors.BuildStateActor.BuildStateMessage
import concurrent.duration.{FiniteDuration, Duration}
import play.api.libs.json.JsValue

object JenkinsMonitoring {
  trait JenkinsMonitor[T] {
    def query: String
    def transformQueryResponse(json: JsValue): Option[T]
    def queryPeriod: FiniteDuration
  }

  trait JenkinsMonitoringMessage
  case class RegisterMonitoringListener(listener: ActorRef) extends JenkinsMonitoringMessage
  case object Query extends JenkinsMonitoringMessage
}

class JenkinsMonitoring(httpClient: ActorRef, monitor: JenkinsMonitor[BuildStateMessage]) extends Actor with ActorLogging {
  import context.dispatcher

  var listeners:List[ActorRef] = Nil

  self ! Query

  override def receive = {
    case RegisterMonitoringListener(newListener) => listeners = newListener :: listeners
    case Query => httpClient ! JsonQuery(monitor.query)
    case JsonReply(json) => {
      monitor.transformQueryResponse(json) foreach {
        buildStateMessage => listeners.foreach(_ ! buildStateMessage)
      }

      context.system.scheduler.scheduleOnce(monitor.queryPeriod, self, Query);
    }
  }
}
