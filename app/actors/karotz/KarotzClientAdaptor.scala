package actors.karotz

import akka.actor._
import org.peripheralware.karotz.client.KarotzClient
import org.peripheralware.karotz.publisher.KarotzActionPublisher
import org.peripheralware.karotz.action.tts.SpeakAction
import akka.util.duration._
import akka.util.Timeout
import akka.pattern.ask
import actors.karotz.KarotzClientAdaptor._
import actors.karotz.KarotzClientAdaptor.SpeechActionMessage
import actors.karotz.KarotzClientAdaptor.LightFadeActionMessage
import actors.karotz.KarotzClientAdaptor.LightActionMessage
import config.KarotzConfig
import actors.karotz.KarotzClientAdaptor.SpeechActionMessage
import actors.karotz.KarotzClientAdaptor.LightFadeActionMessage
import actors.karotz.KarotzClientAdaptor.LightActionMessage
import akka.dispatch.{Await, Future}
import org.peripheralware.karotz.action.KarotzAction
import org.peripheralware.karotz.action.led.{LedFadeAction, LedLightAction}
import akka.dispatch.ExecutionContext
import actors.PrioritisedMessageFunnel.{NoMessagesQueued, ReplyWithNextKarotzCommand, FunnelMessage}

object KarotzClientAdaptor {

  abstract class KarotzClientAdaptorMessage
  case object Tick extends KarotzClientAdaptorMessage

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

  case class SpeechActionMessage(message: String) extends FunnelMessage
  case class LightActionMessage(ledColour: LedColour) extends FunnelMessage
  case class LightFadeActionMessage(ledColour: LedColour, period: Long) extends FunnelMessage

  sealed trait State
  case object Uninitialised extends State
  case object WaitingForInteractiveModeToStart extends State
  case object WaitingForInteractiveModeToStop extends State
  case object InInteractiveModeAndWaitingForFunnelPoll extends State
  case object InInteractiveModeAndWaitingForFunnelReply extends State
  case object InInteractiveModeAndWaitingForKarotzReply extends State

  case class KarotzClientData(client: KarotzClient, karotzActionPublisher: KarotzActionPublisher, restartRequested: Boolean) {
    def this(client: KarotzClient) = this(client, new KarotzActionPublisher(client), false)
  }

  case object StartInteractiveMode
  case object StopInteractiveMode
  case object InteractiveModeStarted
  case object PollForMessages
  case object KarotzMessageProcessed

}


class KarotzClientAdaptor(funnel: ActorRef, karotzConfig: KarotzConfig) extends Actor with FSM[State, KarotzClientData] {


  implicit val timeout = Timeout(60 seconds)

  startWith(Uninitialised, new KarotzClientData(new KarotzClient(karotzConfig.apiKey, karotzConfig.secretKey, karotzConfig.installId)))

  private def startInteractiveMode(data: KarotzClientData): Boolean = {
    try {
      //data.client.startInteractiveMode()
    } catch {
      case _ => {
        System.out.println("unable to start")
        false
      }
    }
    true;
  }


  private def startInteractiveModeAndReply(data: KarotzClientData) {

    implicit val executor = context.dispatcher
    val startInteractiveModeFuture = Future[Boolean] {
      startInteractiveMode(data)
    }

    for (result <- startInteractiveModeFuture) yield {
      if (result) self ! InteractiveModeStarted
    }
  }

  private def stopInteractiveMode(data: KarotzClientData): Boolean = {
    try {
//      data.client.stopInteractiveMode()
    } catch {
      case _ => {
        System.out.println("unable to stop")
        false
      }
    }
    true;
  }

  private def stopInteractiveModeAndReply(data: KarotzClientData) {

    implicit val executor = context.dispatcher
    val stopInteractiveModeFuture = Future[Boolean] {
      stopInteractiveMode(data)
    }

    for (result <- stopInteractiveModeFuture) yield {
      if (result) self ! Uninitialised
    }
  }

  when(Uninitialised) {
    case Event(StartInteractiveMode, data) => {
      startInteractiveModeAndReply(data)

      goto(WaitingForInteractiveModeToStart)
    }
  }

  when(WaitingForInteractiveModeToStart) {
    case Event(InteractiveModeStarted, _) => {
      log.info("Karotz Client initialised and ready for messages")

      context.system.scheduler.scheduleOnce(20 seconds, self, StopInteractiveMode);
      goto(InInteractiveModeAndWaitingForFunnelPoll)
    }
  }

  when(WaitingForInteractiveModeToStop) {
    case Event(Uninitialised, data) => {
      log.info("Karotz Client interactive mode has been stopped. Will now restart.")

      startInteractiveModeAndReply(data)

      goto(WaitingForInteractiveModeToStart)
    }
  }

  when(InInteractiveModeAndWaitingForFunnelPoll) {
    case Event(PollForMessages, _) => {
      log.info("Polling for message")
      funnel ! ReplyWithNextKarotzCommand
      goto(InInteractiveModeAndWaitingForFunnelReply)
    }

    case Event(StopInteractiveMode, data) => {
      stopInteractiveModeAndReply(data)
      goto(WaitingForInteractiveModeToStop)
    }
  }

  private def performKarotzAction(data: KarotzClientData, karotzAction: KarotzAction): Boolean = {
    try {
      //data.karotzActionPublisher.performAction(karotzAction)
    } catch {
      case _ => {
        System.out.println("unable to start")
        false
      }
    }
    true;
  }

  private def performKarotzActionAndReply(data: KarotzClientData, karotzAction: KarotzAction) {

    implicit val executor = context.dispatcher
    val performKarotzActionFuture = Future[Boolean] {
      performKarotzAction(data, karotzAction)
    }

    for (result <- performKarotzActionFuture) yield {
      if (result) self ! KarotzMessageProcessed
    }
  }

  when(InInteractiveModeAndWaitingForFunnelReply) {
    case Event(SpeechActionMessage(message: String), data) => {
      log.info("sending [{}] to Karotz Client", message);
      performKarotzActionAndReply(data, new SpeakAction((message)))
      goto(InInteractiveModeAndWaitingForKarotzReply)
    }
    case Event(LightActionMessage(ledColour: LedColour), data) => {
      log.info("Setting Karotz Client to colour [{}]", ledColour.colour);
      performKarotzActionAndReply(data, new LedLightAction(ledColour.colour))
      goto(InInteractiveModeAndWaitingForKarotzReply)
    }
    case Event(LightFadeActionMessage(ledColour: LedColour, period: Long), data) => {
      log.info("Setting Karotz Client to fade from colour [{}] over a period of [{}]", ledColour.colour, period);
      performKarotzActionAndReply(data, new LedFadeAction(ledColour.colour, period))
      goto(InInteractiveModeAndWaitingForKarotzReply)
    }
    case Event(NoMessagesQueued, _) => {
      log.info("No messages queued")
      goto(InInteractiveModeAndWaitingForFunnelPoll)
    }
    case Event(StopInteractiveMode, data) => {
      log.info("Stop message received. Will be actioned after next funnel message processed")
      stay() using KarotzClientData(data.client, data.karotzActionPublisher, true)
    }
  }

  when(InInteractiveModeAndWaitingForKarotzReply) {
    case Event(KarotzMessageProcessed, data) => {
      if (data.restartRequested) {
        stopInteractiveModeAndReply(data)
        goto(WaitingForInteractiveModeToStop)
      } else {
        goto(InInteractiveModeAndWaitingForFunnelPoll)
      }
    }
  }

  initialize

  override def preStart() = {
    context.system.scheduler.scheduleOnce(500 milliseconds, self, StartInteractiveMode);
  }

  onTransition {
    case _ -> InInteractiveModeAndWaitingForFunnelPoll => {
      context.system.scheduler.scheduleOnce(5 seconds, self, PollForMessages);
    }
  }

  onTermination {
    case StopEvent(_, _, data) => {
      log.debug("Shutting down Karotz Client");
      //data.client.stopInteractiveMode()
    }
  }

}