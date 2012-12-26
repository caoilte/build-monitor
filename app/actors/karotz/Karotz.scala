package actors.karotz

import actors.PrioritisedMessageFunnel.FunnelMessage
import collection.immutable


object Karotz {


//  sealed trait State
//  case object KarotzShutdownCompleted extends State
//  case object Uninitialised extends State
//  case object WaitingForInteractiveModeToStart extends State
//  case object WaitingForInteractiveModeToStop extends State
//  case object InInteractiveModeAndWaitingForFunnelPoll extends State
//  case object InInteractiveModeAndWaitingForFunnelReply extends State
//  case object InInteractiveModeAndWaitingForKarotzReply extends State

//  sealed trait KarotzClientMessage
//  case object StartInteractiveMode extends KarotzClientMessage
//  case class InteractiveModeStarted(interactiveId: String) extends KarotzClientMessage
//  case object InteractiveModeFailedToStart extends KarotzClientMessage
//  case object StopInteractiveMode extends KarotzClientMessage
//  case object InteractiveModeStopped extends KarotzClientMessage
//  case object PollForMessages extends KarotzClientMessage
//  case object KarotzMessageProcessed extends KarotzClientMessage

  sealed abstract class LedColour {
    def colour: String
  }
  case object RedLed extends LedColour {
    def colour = "FF0000"
  }
  case object GreenLed extends LedColour {
    def colour = "00FF00"
  }
  case object YellowLed extends LedColour {
    def colour = "FFFF00"
  }
  case object BlueLed extends LedColour {
    def colour = "0000FF"
  }

  case class KarotzMessage(action: KarotzAction) extends FunnelMessage;


  trait KarotzAction
  case object PingAction extends KarotzAction
  abstract class ParameterisedKarotzAction extends KarotzAction {
    def getParams: immutable.HashMap[String, String];
    def getAPISuffix: String;
  }

  case class SpeechAction(message: String) extends KarotzAction{
    def getParams = immutable.HashMap("action" -> "speak", "lang"-> "EN", "text" -> message);
    def getAPISuffix = "tts";
  }
  abstract class LEDAction extends KarotzAction {
    def getColorParam(ledColour: Option[LedColour]): (String, String) = {
      ledColour match {
        case Some(ledColour) => ("color" -> ledColour.colour)
        case None => ("color" -> "")
      }
    }
    def getAPISuffix = "led";

  }
  case class LightAction(ledColour: Option[LedColour]) extends LEDAction {
    def getParams = immutable.HashMap("action" -> "light") + getColorParam(ledColour)
  }
  case class LightPulseAction(ledColour: Option[LedColour], period: Int, duration: Int) extends LEDAction {
    def getParams = {
      immutable.HashMap("action" -> "pulse", "period" -> period.toString, "pulse" -> duration.toString) + getColorParam(ledColour)
    };
  }
}
