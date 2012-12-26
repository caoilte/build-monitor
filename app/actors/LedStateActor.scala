package actors

import akka.actor.{FSM, ActorRef, ActorLogging, Actor}
import akka.util.{Deadline, Timeout}
import akka.util.duration._
import collection.immutable.HashMap
import actors.BuildStateActor._
import karotz.Karotz._
import actors.PrioritisedMessageFunnel.{LowPriorityMessage, HighPriorityMessage}
import LedStateActor._
import actors.PrioritisedMessageFunnel.HighPriorityMessage
import actors.PrioritisedMessageFunnel.LowPriorityMessage
import actors.PrioritisedMessageFunnel.HighPriorityMessage
import actors.PrioritisedMessageFunnel.LowPriorityMessage
import akka.event.LoggingReceive
;


object LedStateActor {
  case object LedStateRequest;


}


class LedStateActor(funnel: ActorRef) extends Actor with ActorLogging {
  var brokenBuildsMap = new HashMap[String, Boolean];


  def receive = LoggingReceive {
    case BuildStateNotification(state, BuildStateData(buildInformation, committers)) => {
      brokenBuildsMap = brokenBuildsMap + ((buildInformation.jobName, !state.isWorking));

      sendLedMessage(state);
    }
    case LedStateRequest => sender ! KarotzMessage(LightAction(Some(ledColour)))
  }

  def sendLedMessage(state: BuildStateActor.State) {

    // pulse is the colour that it will end up in.

    state match {
      case Healthy => {
        funnel forward LowPriorityMessage(KarotzMessage(LightPulseAction(Some(GreenLed), 1000, 15000)))
      }
      case JustFixed => {
        funnel forward HighPriorityMessage(KarotzMessage(LightPulseAction(Some(GreenLed), 1000, 30000)))
      }
      case JustBroken => {
        funnel forward HighPriorityMessage(KarotzMessage(LightPulseAction(Some(RedLed), 1000, 30000)))
      }
      case StillBroken => {
        funnel forward HighPriorityMessage(KarotzMessage(LightPulseAction(Some(RedLed), 1000, 30000)))
      }
      case Unknown => {
        log.warning("LED State Requested when situation is unknown")
      }
    }
  }


  def areBuildsBroken = brokenBuildsMap.exists(_._2);

  def ledColour: LedColour = {
    val areBuildsBroken = brokenBuildsMap.exists(_._2);

    if (areBuildsBroken) RedLed else GreenLed
  }



}
