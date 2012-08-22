package actors

import akka.actor.{ActorRef, ActorLogging, Actor}
import akka.util.Timeout
import akka.util.duration._
import actors.BuildStateActor._
import collection.immutable.HashMap
import karotz.KarotzClientAdaptor._
import actors.BuildStateActor.BuildStateData
import karotz.KarotzClientAdaptor.LightFadeActionMessage
import actors.PrioritisedMessageFunnel.{LowPriorityMessage, HighPriorityMessage}


class LedStateActor(funnel: ActorRef) extends Actor with ActorLogging {

  implicit val timeout = Timeout(5 seconds)

  var brokenBuildsMap: HashMap[String, Boolean] = new HashMap[String, Boolean]();

  def sendLedMessage(stateHasChanged: Boolean, colour: LedColour, fadeoutPeriod: Long) {
    if (stateHasChanged) {
      funnel forward HighPriorityMessage(LightActionMessage(colour))
    } else {
      funnel forward LowPriorityMessage(LightFadeActionMessage(colour, fadeoutPeriod))
    }
  }

  def areBuildsBroken = brokenBuildsMap.exists(_._2);


  protected def receive = {

    case (state, BuildStateData(jobName, buildsSinceLastFailure, breakageAuthors, sinceBreakageAuthors)) => {
      val wereBuildsBroken = areBuildsBroken;
      val oldBrokenBuildsMapSize = brokenBuildsMap.size
      brokenBuildsMap = brokenBuildsMap + ((jobName, false));

      val stateHasChanged = (oldBrokenBuildsMapSize == 0) || areBuildsBroken != wereBuildsBroken

      state match {
        case Healthy => sendLedMessage(stateHasChanged, GreenLed, 1)
        case JustFixed => sendLedMessage(stateHasChanged, GreenLed, 4)
        case JustBroken => sendLedMessage(stateHasChanged, RedLed, 4)
        case StillBroken => sendLedMessage(stateHasChanged, RedLed, 2)
      }
    }



  }

}
