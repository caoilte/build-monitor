package actors.karotz

import net.violet.karotz.client.{Lang, KarotzIOHandler, Client}
import config.KarotzConfig
import akka.actor.{ActorRef, FSM, Actor}
import scala.concurrent.Await
import scala.concurrent.duration._
import net.violet.voos.message.MessageManager.{VReturnCode, InteractiveMode, VoosMsg}
import java.util.UUID
import net.violet.voos.message.MessageManager.InteractiveMode.Action
import net.violet.voos.message.VoosMsgCallback
import actors.karotz.Karotz._
import actors.karotz.KarotzClientManager.{Data, State}
import actors.karotz.Karotz.LightPulseAction
import actors.karotz.Karotz.SpeechAction
import actors.karotz.Karotz.LightAction
import actors.BuildMonitoringSupervisor.ShutdownRequest
import org.apache.mina.core.session.IoSession

object KarotzClientManager {
  import actors.karotz.Karotz._

  sealed trait State
  case object KarotzShutdownCompleted extends State
  case object Uninitialised extends State
  case object Initialised extends State
  case object WaitingForKarotzToReply extends State
  case object WaitingForInteractiveModeToStop extends State

  sealed trait KarotzClientManagerMessage
  case object StartInteractiveMode extends KarotzClientManagerMessage
  case object InteractiveModeStarted extends KarotzClientManagerMessage
  case object ShutdownComplete extends KarotzClientManagerMessage
  case class KarotzMessageFailedToProcess(val session: IoSession, val cause: Throwable) extends KarotzClientManagerMessage
  case object KarotzMessageProcessed extends KarotzClientManagerMessage
  case class PerformAction(action: KarotzAction)

  sealed trait Data;
  case object StartupData extends Data;
  case class StartedData(client: Client) extends Data;
  case class SenderRefData(originalSender: ActorRef) extends Data
  case class ShutdownData(requester: ActorRef) extends Data

  class InteractiveModeFailedToStart extends Exception("Interactive mode failed to start")
  class KarotzMessageSendingFailed(val msg: VoosMsg) extends Exception("Karotz rejected message")
  class KarotzIOException(val session: IoSession, val cause: Throwable) extends Exception("IO Error occurred sending message to Karotz")
  class KarotzIOTimedOut extends Exception("Timed out waiting for Karotz to reply to a message")

}

class KarotzClientManager(config: KarotzConfig) extends Actor with FSM[State, Data] {
  import actors.karotz.KarotzClientManager._

  val ioHandler = new KarotzIOHandler {
    override def exceptionCaught(session: IoSession, cause: Throwable) {
      self ! KarotzMessageFailedToProcess(session, cause)
    }
  }

  val client = new Client(ioHandler)

  startWith(Uninitialised, StartupData);

  when(Uninitialised) {
    case Event(StartInteractiveMode, data) => {
      client.connect(config.ipAddress, config.port, 5000);

      val interactiveModeStarted = client.startInteractiveMode();

      if (interactiveModeStarted) {
        log.info("Karotz Client initialised and ready for messages");

        sender ! InteractiveModeStarted
        goto(Initialised)
      } else {
        client.disconnect()
        throw new InteractiveModeFailedToStart
      }
    }
    case Event(ShutdownRequest, _) => shutdown(sender)
  }

  def shutdown(onCompleteListener: ActorRef) = {
    client.disconnect();
    log.info("Disconnected from Karotz. Karotz Shutdown Complete")
    onCompleteListener ! ShutdownComplete
    goto(KarotzShutdownCompleted);
  }

  when(KarotzShutdownCompleted) {
    case Event(message, data) => stay()
  }


  private def getInteractiveId(): String = {
    val f = classOf[Client].getDeclaredField("interactiveId");
    f.setAccessible(true);
    (f.get(client)).asInstanceOf[String];
  }

  class AkkaVoosMsgCallback(callback: Any = KarotzMessageProcessed) extends VoosMsgCallback {
    def onVoosMsg(msg: VoosMsg) {
      val responseCode = msg.getEvent().getCode();
      if (responseCode.equals(VReturnCode.OK)) {
        self ! callback
        log.debug("Karotz Reply successfully processed");
      } else if (responseCode.equals(VReturnCode.TERMINATED)) {
        // do nothing
      } else if (responseCode.equals(VReturnCode.CANCELLED)) {
        // normal behaviour. if we send another action before previous has finished.
      } else {
        throw new KarotzMessageSendingFailed(msg)
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
      case PingAction => ping()
    }
  }

  private def ping(): Unit = {
    val voosMsg:VoosMsg = VoosMsg.newBuilder().setId(UUID.randomUUID().toString()).setInteractiveId(getInteractiveId()).setPing(net.violet.voos.message.MessageManager.Ping.newBuilder().build()).build();
    client.send(voosMsg, new AkkaVoosMsgCallback);
  }

  private def shutdown(): Unit = {
    val voosMsg:VoosMsg = VoosMsg.newBuilder().setId(UUID.randomUUID().toString()).setInteractiveId(getInteractiveId()).setInteractiveMode(InteractiveMode.newBuilder().setAction(Action.STOP).build()).build()
    log.info("Sending Shutdown Request to Karotz")
    client.send(voosMsg, new AkkaVoosMsgCallback(ShutdownComplete));
  }

  when(Initialised) {
    case Event(ShutdownRequest, data) => {
      shutdown()
      goto (WaitingForInteractiveModeToStop) using (ShutdownData(sender))
    }
    case Event(PerformAction(action), data) => {
      performAction(action)
      goto(WaitingForKarotzToReply) using SenderRefData(sender)
    }
    case Event(StartInteractiveMode, data) => {
      sender ! InteractiveModeStarted
      stay()
    }
  }

  when(WaitingForKarotzToReply, stateTimeout = 10 seconds) {
    case Event(KarotzMessageProcessed, SenderRefData(originalSender)) => {
      originalSender forward KarotzMessageProcessed
      goto(Initialised) using StartupData
    }
    case Event(KarotzMessageFailedToProcess(session, cause), data) => {
      throw new KarotzIOException(session, cause)
    }
    case Event(StateTimeout, data) => {
      throw new KarotzIOTimedOut
    }
    case Event(ShutdownRequest, data) => {
      shutdown()
      goto (WaitingForInteractiveModeToStop) using (ShutdownData(sender))
    }
  }


  when(WaitingForInteractiveModeToStop) {
    case Event(ShutdownComplete, ShutdownData(onCompleteListener)) => shutdown(onCompleteListener)
    case Event(event, data) => {
      log.warning("Event occurred while waiting for Shutdown to Complete. Will ignore.")
      stay()
    }
  }

}
