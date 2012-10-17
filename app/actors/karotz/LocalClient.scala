package actors.karotz

import net.violet.karotz.client.{Lang, Client, KarotzIOHandler}
import akka.actor.{ActorRef, FSM, Actor}
import akka.util.Deadline
import actors.karotz.LocalClient._
import actors.karotz.Karotz._
import config.KarotzConfig
import actors.BuildMonitoringSupervisor.{ShutdownComplete, ShutdownRequest}
import akka.util.duration._
import akka.util.{Deadline, Timeout}
import actors.LedStateActor.LedStateRequest
import actors.PrioritisedMessageFunnel.{NoMessagesQueued, ReplyWithNextKarotzCommand}
import net.violet.voos.message.VoosMsgCallback
import net.violet.voos.message.MessageManager.{InteractiveMode, VReturnCode, VoosMsg}

import net.violet.voos.message.MessageManager.InteractiveMode.Action;
import java.util.UUID

object LocalClient {

  sealed trait Data;
  case object StartupData extends Data;
  case class KarotzClientData(karotzActionDeadline: Deadline,
                              currentLedColour: Option[LedColour], permanentColourDeadLine: Option[Deadline]) extends Data {
    def this(karotzActionDeadline: Deadline) = {
      this(karotzActionDeadline, None, None)
    }
    def this(karotzActionDeadline: Deadline, ledColour: Option[LedColour]) = {
      this(karotzActionDeadline, None, None)
    }
  }
  case class ShutdownData(onCompleteListener: ActorRef) extends Data;


  case object Ping;
}

class LocalClient(val karotzIOHandler: KarotzIOHandler, config: KarotzConfig, funnel: ActorRef, ledStateActor: ActorRef) extends Actor with FSM[State, Data] {

  val client = new Client(karotzIOHandler)
  client.connect(config.ipAddress, config.port, 5000);

  startWith(Uninitialised, StartupData);

  when(Uninitialised) {
    case Event(StartInteractiveMode, data) => {
      val interactiveModeStarted = client.startInteractiveMode();

      if (interactiveModeStarted) {
        log.info("Karotz Client initialised and ready for messages");

        ledStateActor ! LedStateRequest

        goto(InInteractiveModeAndWaitingForFunnelReply) using new KarotzClientData(13.minutes.fromNow)
      } else {
        log.info("Interactive mode failed to start. Will schedule another attempt in 30 seconds");

        context.system.scheduler.scheduleOnce(30 seconds, self, StartInteractiveMode);
        goto(Uninitialised)

      }
    }
    case Event(ShutdownRequest, _) => {
      client.disconnect();
      sender ! ShutdownComplete
      goto(KarotzShutdownCompleted);
    }
  }


  when(KarotzShutdownCompleted) {
    case Event(message, data) => stay()
  }

  when(InInteractiveModeAndWaitingForFunnelReply) {
    case Event(KarotzMessage(action), KarotzClientData(karotzActionDeadline, currentLedColour, permanentColourDeadline)) => {

      val (newLedColour: Option[LedColour], newPermanentColourDeadline: Option[Deadline]) = action match {
        case pulse:LightPulseAction => (Some(pulse.ledColour), Some((pulse.duration).milliseconds.fromNow))
        case light:LightAction => (light.ledColour, None)
        case _ => (currentLedColour, permanentColourDeadline)
      }

      log.info("KarotzMessage {} received. new LED={}, new Deadline={}", action, newLedColour, newPermanentColourDeadline);

      performAction(action);

      goto(InInteractiveModeAndWaitingForKarotzReply) using KarotzClientData(13.minutes.fromNow, newLedColour, newPermanentColourDeadline)

    }
    case Event(NoMessagesQueued, _) => {
      log.debug("No messages queued")
      goto(InInteractiveModeAndWaitingForFunnelPoll)
    }
    case Event(StopInteractiveMode, data) => {
      log.info("Stop message received. Will be actioned after next funnel message processed")
      stay() using data
    }
  }

  when(InInteractiveModeAndWaitingForKarotzReply, stateTimeout = 30 seconds) {
    case Event(KarotzMessageProcessed, data) => {
      goto(InInteractiveModeAndWaitingForFunnelPoll)
    }
    case Event(StateTimeout, KarotzClientData(karotzActionDeadline, currentLedColour, permanentColourDeadline)) => {
      log.warning("Timed out waiting for Karotz to reply to a message. Will abandon.")
      goto(InInteractiveModeAndWaitingForFunnelPoll) using KarotzClientData(1.minute.fromNow, currentLedColour, permanentColourDeadline)
    }
  }

  when(InInteractiveModeAndWaitingForFunnelPoll) {
    case Event(PollForMessages, KarotzClientData(karotzActionDeadline, currentLedColour, permanentColourDeadline)) => {

      permanentColourDeadline match {
        case Some(deadline) => {
          if (deadline.isOverdue()) {
            log.info("Ending temporary LED State by requesting permanent LED Colour state");

            ledStateActor ! LedStateRequest
            goto (InInteractiveModeAndWaitingForFunnelReply) using
              (KarotzClientData(karotzActionDeadline, currentLedColour, None))
          } else {
            pollFunnelWithNextCommand(currentLedColour)
          }
        }
        case None => pollFunnelWithNextCommand(currentLedColour)
      }
    }

    case Event(KarotzMessageProcessed, KarotzClientData(karotzActionDeadline, currentLedColour, permanentColourDeadline)) => {
      val newData = new KarotzClientData(13.minutes.fromNow)
      scheduleNextFunnelPoll(newData)
      stay() using newData
    }
  }

  def pollFunnelWithNextCommand(existingLedState: Option[LedColour]) = {

    log.debug("Polling for message")
    funnel ! ReplyWithNextKarotzCommand(existingLedState)
    goto(InInteractiveModeAndWaitingForFunnelReply)
  }

  class AkkaVoosMsgCallback extends VoosMsgCallback {
    def onVoosMsg(msg: VoosMsg) {
      val responseCode = msg.getEvent().getCode();
      if (responseCode.equals(VReturnCode.OK)) {
        self ! KarotzMessageProcessed
        log.info("Karotz Reply successfully processed");
      } else if (responseCode.equals(VReturnCode.TERMINATED)) {
        // do nothing
      } else if (responseCode.equals(VReturnCode.CANCELLED)) {
        // normal behaviour. if we send another action before previous has finished.
      } else {
        log.error("something went wrong "+msg);
      }
    }

  }

  private def performAction(action: KarotzAction):Unit = {

    action match {
      case SpeechAction(text) => {
        log.info("sending text "+text);
        //self ! KarotzMessageProcessed
        client.sendTts(text, Lang.EN_GB, new AkkaVoosMsgCallback())
      };
      case LightAction(ledColour) => {
        log.info("sending led "+ledColour);
        //self ! KarotzMessageProcessed
        client.sendLed(ledColour.map(_.colour).getOrElse(""), new AkkaVoosMsgCallback);
      }
      case LightPulseAction(ledColour, period, pulse) => {
        log.info("sending pulse "+ledColour);
        //self ! KarotzMessageProcessed
        client.sendLedPulse(ledColour.map(_.colour).getOrElse(""), period, pulse, new AkkaVoosMsgCallback);
      }
    }
  }

  private def getInteractiveId(): String = {
    val f = classOf[Client].getDeclaredField("interactiveId");
    f.setAccessible(true);
    (f.get(client)).asInstanceOf[String];
  }

  private def ping(): Unit = {

    val voosMsg:VoosMsg = VoosMsg.newBuilder().setId(UUID.randomUUID().toString()).setInteractiveId(getInteractiveId()).setPing(net.violet.voos.message.MessageManager.Ping.newBuilder().build()).build();
    client.send(voosMsg, new AkkaVoosMsgCallback);
  }

  private def shutdown(): Unit = {
    val voosMsg:VoosMsg = VoosMsg.newBuilder().setId(UUID.randomUUID().toString()).setInteractiveId(getInteractiveId()).setInteractiveMode(InteractiveMode.newBuilder().setAction(Action.STOP).build()).build()
    client.send(voosMsg, new AkkaVoosMsgCallback);

  }


  when(WaitingForInteractiveModeToStop) {
    case Event(KarotzMessageProcessed, ShutdownData(onCompleteListener)) => {
      onCompleteListener ! ShutdownComplete
      client.disconnect();
      goto(KarotzShutdownCompleted)
    }
  }

  whenUnhandled {

    case Event(ShutdownRequest, _) => {
      log.info("sending shutdown message to bunny");
      shutdown()
      goto (WaitingForInteractiveModeToStop) using (ShutdownData(sender))
    }
  }


  onTransition {
    case _ -> InInteractiveModeAndWaitingForFunnelPoll => scheduleNextFunnelPoll(nextStateData)
  }

  def scheduleNextFunnelPoll(data: Data) = data match {
    case KarotzClientData(karotzActionDeadline, currentLedColour, permanentColourDeadline) => {
      if (karotzActionDeadline.isOverdue()) {
        log.info("No message has been sent to the build bunny for 13 minutes so will ping it to keep the session alive")
        ping();
      } else {
        context.system.scheduler.scheduleOnce(3 seconds, self, PollForMessages);
      }
    }
    case _ => throw new IllegalStateException("No Data");

  }


}
