package actors.karotz

import akka.actor._
import actors.karotz.KarotzThroughputManager.{State, Data}
import actors.karotz.KarotzClientManager._
import actors.LedStateActor.LedStateRequest
import akka.util.{Duration, Deadline, Timeout}
import actors.karotz.Karotz._
import akka.util.duration._
import actors.karotz.Karotz.KarotzMessage
import actors.karotz.Karotz.LightPulseAction
import scala.Some
import actors.PrioritisedMessageFunnel.{ReplyWithNextKarotzCommand, NoMessagesQueued}
import actors.karotz.Karotz.KarotzMessage
import actors.karotz.Karotz.LightPulseAction
import scala.Some
import actors.PrioritisedMessageFunnel.ReplyWithNextKarotzCommand
import actors.karotz.KarotzThroughputManager.State
import actors.karotz.Karotz.LightAction
import actors.karotz.Karotz.KarotzMessage
import actors.karotz.Karotz.LightPulseAction
import scala.Some
import actors.PrioritisedMessageFunnel.ReplyWithNextKarotzCommand
import actors.karotz.Karotz.LightAction
import akka.actor.SupervisorStrategy.Stop
import actors.ExponentialBackOff
import actors.BuildMonitoringSupervisor.ShutdownRequest

object KarotzThroughputManager {

  sealed trait State;
  case object Uninitialised extends State
  case object Shutdown extends State
  case object WaitingForFunnelReply extends State
  case object WaitingForFunnelPoll extends State
  case object WaitingForKarotzClientReply extends State

  sealed trait Data;
  case object StartupData extends Data;

  case class KarotzClientData(karotzClientManager: ActorRef, karotzActionDeadline: Deadline,
                              currentLedColour: Option[LedColour], permanentColourDeadLine: Option[Deadline]) extends Data {
    def this(karotzClientManager: ActorRef, karotzActionDeadline: Deadline) = {
      this(karotzClientManager, karotzActionDeadline, None, None)
    }
    def this(karotzClientManager: ActorRef, karotzActionDeadline: Deadline, ledColour: Option[LedColour]) = {
      this(karotzClientManager, karotzActionDeadline, None, None)
    }
  }


  sealed trait KarotzThroughputManagerMessage
  case object PollForMessages extends KarotzThroughputManagerMessage
  case object Initialise extends KarotzThroughputManagerMessage

}


class KarotzThroughputManager(karotzClientProps: Props, funnel: ActorRef, ledStateActor: ActorRef) extends Actor with FSM[State, Data] {
  import KarotzThroughputManager._

  startWith(Uninitialised, StartupData)


  var backOff = ExponentialBackOff(2 seconds, 12, true)

  override val supervisorStrategy =  OneForOneStrategy(-1, Duration.Inf) {
    case e:KarotzMessageSendingFailed => {
      log.error("Karotz Message Sending Failed {}", e.msg)
      Stop
    }
    case e:InteractiveModeFailedToStart => {
      log.error("Interactive Mode Failed to Start")
      Stop
    }
    case e:KarotzIOException => {
      log.error("Karotz IO Error Occured {}", e.cause)
      Stop

    }

    case _: Exception ⇒ Stop
  }

  self ! Initialise

  when(Uninitialised) {
    case (Event(Initialise, data)) => {
      var karotzClientManager = context.actorOf(karotzClientProps, "karotz-client-manager")
      context.watch(karotzClientManager)

      karotzClientManager ! StartInteractiveMode

      goto(WaitingForKarotzClientReply) using new KarotzClientData(karotzClientManager, 13.minutes.fromNow)
    }
  }

  when(Shutdown) {
    case (Event(event, data)) => stay()
  }

  when(WaitingForFunnelReply) {
    case Event(KarotzMessage(action), KarotzClientData(karotzClientManager, karotzActionDeadline, currentLedColour, permanentColourDeadline)) => {
      def newKarotzClientData(newLedColour: Option[LedColour], newPermanentColourDeadline: Option[Deadline]) = {
        KarotzClientData(karotzClientManager, 13.minutes.fromNow, newLedColour, newPermanentColourDeadline)
      }

      val calculatedNewKarotzClientData = action match {
        case pulse:LightPulseAction => newKarotzClientData(pulse.ledColour, Some((pulse.duration).milliseconds.fromNow))
        case light:LightAction => newKarotzClientData(light.ledColour, None)
        case _ => newKarotzClientData(currentLedColour, permanentColourDeadline)
      }

      log.info("KarotzMessage {} received. new LED={}, new Deadline={}", action, calculatedNewKarotzClientData.currentLedColour,
        calculatedNewKarotzClientData.permanentColourDeadLine);

      karotzClientManager ! PerformAction(action)

      goto(WaitingForKarotzClientReply) using calculatedNewKarotzClientData
    }
    case Event(NoMessagesQueued, _) => {
      log.debug("No messages queued")
      goto(WaitingForFunnelPoll)
    }
  }

  when(WaitingForFunnelPoll) {
    case Event(PollForMessages, KarotzClientData(karotzClientManager, karotzActionDeadline, currentLedColour, permanentColourDeadline)) => {

      permanentColourDeadline match {
        case Some(deadline) => {
          if (deadline.isOverdue()) {
            log.info("Ending temporary LED State by requesting permanent LED Colour state");

            ledStateActor ! LedStateRequest
            goto (WaitingForFunnelReply) using
              (KarotzClientData(karotzClientManager, karotzActionDeadline, currentLedColour, None))
          } else {
            pollFunnelWithNextCommand(currentLedColour)
          }
        }
        case None => pollFunnelWithNextCommand(currentLedColour)
      }
    }

    case Event(KarotzMessageProcessed, KarotzClientData(karotzClientManager, karotzActionDeadline, currentLedColour, permanentColourDeadline)) => {
      val newData = new KarotzClientData(karotzClientManager, 13.minutes.fromNow)
      scheduleNextFunnelPoll(newData)
      stay() using newData
    }
  }

  def pollFunnelWithNextCommand(existingLedState: Option[LedColour]) = {
    log.info("Polling for message")
    funnel ! ReplyWithNextKarotzCommand(existingLedState)
    goto(WaitingForFunnelReply)
  }

  when(WaitingForKarotzClientReply) { //, stateTimeout = 30 seconds) {
    case Event(InteractiveModeStarted, data) => {
      ledStateActor ! LedStateRequest
      goto(WaitingForFunnelReply) using new KarotzClientData(sender, 13.minutes.fromNow)
    }
    case Event(KarotzMessageProcessed, data) => {
      log.info("karotz reply processed")
      goto(WaitingForFunnelPoll)
    }
//    case Event(StateTimeout, KarotzClientData(karotzClientManager, karotzActionDeadline, currentLedColour, permanentColourDeadline)) => {
//      log.warning("Timed out waiting for Karotz to reply to a message. Will abandon.")
//      karotzClientManager ! StartInteractiveMode
//      goto(Uninitialised) using StartupData
//    }
  }

  whenUnhandled {
    case Event(Terminated(failedRef), data) => {
      backOff = backOff.nextBackOff
      log.error("Karotz Client Manager Failed. This is the {} failure. Scheduling restart in {}", backOff.retries, backOff.waitTime)
      context.system.scheduler.scheduleOnce(backOff.waitTime, self, Initialise)
      goto(Uninitialised) using StartupData
    }
    case Event(ShutdownRequest, data) => data match {
      case KarotzClientData(karotzClientManager, karotzActionDeadline, currentLedColour, permanentColourDeadline) => {
        karotzClientManager forward ShutdownRequest
        goto(Uninitialised) using StartupData
      }
      case _ => {
        sender ! ShutdownComplete
        goto(Uninitialised)
      }
    }
  }


  onTransition {
    case _ -> WaitingForFunnelPoll => {
      backOff.reset()
      scheduleNextFunnelPoll(nextStateData)
    }
  }


  def scheduleNextFunnelPoll(data: Data) = data match {
    case KarotzClientData(karotzClientManager, karotzActionDeadline, currentLedColour, permanentColourDeadline) => {
      if (karotzActionDeadline.isOverdue()) {
        log.info("No message has been sent to the build bunny for 13 minutes so will ping it to keep the session alive")
        karotzClientManager ! PerformAction(PingAction)
      } else {
        context.system.scheduler.scheduleOnce(3 seconds, self, PollForMessages);
      }
    }
    case _ => throw new IllegalStateException("No Data");
  }
}
