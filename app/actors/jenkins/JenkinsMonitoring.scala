package actors.jenkins

import akka.actor.{ActorRef, ActorLogging, Actor}
import net.liftweb.json.JsonAST.JValue
import actors.jenkins.JenkinsMonitoring._
import actors.jenkins.JenkinsClientManager.{JsonReply, JsonQuery}
import actors.BuildStateActor.BuildStateMessage

object JenkinsMonitoring {
  trait JenkinsMonitor[T] {
    def query: String
    def transformQueryResponse(json: JValue): Option[T]
    def queryPeriod: akka.util.Duration
  }

  trait JenkinsMonitoringMessage
  case class RegisterMonitoringListener(listener: ActorRef) extends JenkinsMonitoringMessage
  case object Query extends JenkinsMonitoringMessage
}

class JenkinsMonitoring(httpClient: ActorRef, monitor: JenkinsMonitor[BuildStateMessage]) extends Actor with ActorLogging {

  var listeners:List[ActorRef] = Nil

  self ! Query

  protected def receive = {
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
