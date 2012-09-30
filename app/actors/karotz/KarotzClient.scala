package actors.karotz

import akka.actor._
import util.Random
import collection.immutable.HashMap
import cc.spray.client.{UnsuccessfulResponseException, DispatchStrategies, HttpConduit}
import cc.spray.http._
import java.net.URLEncoder
import utils.UrlUtils
import xml.{XML, NodeSeq}
import akka.pattern.AskTimeoutException
import actors.LedStateActor.LedStateRequest

import akka.dispatch.{Await, Future}
import akka.util.duration._
import akka.util.{Deadline, Timeout}
import cc.spray.http.MediaTypes._
import java.io.{InputStreamReader, ByteArrayInputStream}
import actors.BuildMonitoringSupervisor._
import actors.karotz.KarotzClient._
import config.KarotzConfig
import collection.immutable
import actors.PrioritisedMessageFunnel.{FunnelMessage, NoMessagesQueued, ReplyWithNextKarotzCommand}
import cc.spray.httpx.unmarshalling.Unmarshaller
import actors.karotz.KarotzClient.InteractiveModeStarted
import actors.karotz.KarotzClient.KarotzMessage
import actors.karotz.KarotzClient.LightPulseAction
import scala.Some
import actors.karotz.KarotzClient.ShutdownData
import actors.karotz.KarotzClient.KarotzClientData
import actors.karotz.KarotzClient.StartupData
import actors.karotz.KarotzClient.LightAction

object KarotzClient {

  /**
   * Base URL for Karotz API Calls
   */
  val KAROTZ_API_PREFIX = "/api/karotz/";

  /**
   * Base URL for the START method (auth)
   */
  val KAROTZ_URL_START = KAROTZ_API_PREFIX + "start";

  /**
   * Base URL for the Interactive mode method
   */
  val KAROTZ_URL_INTERACTIVE_MODE = KAROTZ_API_PREFIX + "interactivemode";

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

  abstract class KarotzAction {
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
  case class LightPulseAction(ledColour: Option[LedColour], period: Long, pulse: Long) extends LEDAction {
    def getParams = {
      immutable.HashMap("action" -> "pulse", "period" -> period.toString, "pulse" -> pulse.toString) + getColorParam(ledColour)
    };
  }


  sealed trait State
  case object KarotzShutdownCompleted extends State
  case object Uninitialised extends State
  case object WaitingForInteractiveModeToStart extends State
  case object WaitingForInteractiveModeToStop extends State
  case object InInteractiveModeAndWaitingForFunnelPoll extends State
  case object InInteractiveModeAndWaitingForFunnelReply extends State
  case object InInteractiveModeAndWaitingForKarotzReply extends State


  sealed trait Data;
  case object StartupData extends Data;
  case class KarotzClientData(interactiveId: String, restartRequested: Boolean,
                              currentLedColour: Option[LedColour], permanentColourDeadLine: Option[Deadline]) extends Data {
    def this(interactiveId: String, restartRequested: Boolean, ledColour: Option[LedColour]) = {
      this(interactiveId, restartRequested, None, None)
    };
    def this(interactiveId: String) = {
      this(interactiveId, false, None, None)
    };
  }
  case class ShutdownData(onCompleteListener: ActorRef) extends Data;


  case object StartInteractiveMode
  case class InteractiveModeStarted(interactiveId: String)
  case object InteractiveModeFailedToStart
  case object StopInteractiveMode
  case object InteractiveModeStopped
  case object PollForMessages
  case object KarotzMessageProcessed

}

class KarotzClient(funnel: ActorRef, ledStateActor: ActorRef, httpClient: ActorRef, karotzConfig: KarotzConfig) extends Actor with FSM[State, Data] {

  implicit val timeout = Timeout(30 seconds);

  val conduit = context.system.actorOf(
    props = Props(new HttpConduit(httpClient, "api.karotz.com")),
    name = "http-conduit"
  )


  implicit val unmarshaller: Unmarshaller[NodeSeq] =
    Unmarshaller[NodeSeq](`application/octet-stream`, `text/xml`, `text/html`, `application/xhtml+xml`) {
      case HttpBody(contentType, buffer) =>
        XML.load(new InputStreamReader(new ByteArrayInputStream(buffer), contentType.charset.nioCharset))
      case EmptyEntity => NodeSeq.Empty
    }


  import HttpConduit._
  val pipeline = sendReceive(conduit) ~> unmarshal[NodeSeq]


  startWith(Uninitialised, StartupData);

  when(Uninitialised) {
    case Event(StartInteractiveMode, data) => {
      startInteractiveMode();
      goto(WaitingForInteractiveModeToStart)
    }
    case Event(ShutdownRequest, data) => {
      sender ! ShutdownComplete;
      goto(KarotzShutdownCompleted);
    }
  }

  when(WaitingForInteractiveModeToStart) {
    case Event(InteractiveModeStarted(interactiveId), StartupData) => {
      log.info("Karotz Client initialised and ready for messages with interactiveId "+interactiveId)

      context.system.scheduler.scheduleOnce(5 minutes, self, StopInteractiveMode);

      ledStateActor ! LedStateRequest
      goto (InInteractiveModeAndWaitingForFunnelReply) using (new KarotzClientData(interactiveId))
    }
    case Event(InteractiveModeFailedToStart, state) => {
      log.info("Interactive mode failed to start. Will schedule another attempt in 30 seconds");

      context.system.scheduler.scheduleOnce(30 seconds, self, StartInteractiveMode);
      goto(Uninitialised)
    }
    case Event(PollForMessages, _) => stay()
  }

  when(WaitingForInteractiveModeToStop) {
    case Event(InteractiveModeStopped, ShutdownData(onCompleteListener)) => {
      log.info("Karotz Client interactive mode has been stopped. Will now signal shutdown complete.")
      onCompleteListener ! ShutdownComplete;
      goto(KarotzShutdownCompleted);
    }
    case Event(InteractiveModeStopped, data) => {
      log.info("Karotz Client interactive mode has been stopped. Will now restart.")
      startInteractiveMode();
      goto(WaitingForInteractiveModeToStart);
    }
  }

  when(KarotzShutdownCompleted) {
    case Event(message, data) => stay()
  }


  def pollFunnelWithNextCommand(existingLedState: Option[LedColour]) = {

    log.debug("Polling for message")
    funnel ! ReplyWithNextKarotzCommand(existingLedState)
    goto(InInteractiveModeAndWaitingForFunnelReply)
  }

  when(InInteractiveModeAndWaitingForFunnelPoll) {
    case Event(PollForMessages, KarotzClientData(interactiveId, restartRequested, currentLedColour, permanentColourDeadline)) => {

      permanentColourDeadline match {
        case Some(deadline) => {
          if (deadline.isOverdue()) {
            log.info("Ending temporary LED State by requesting permanent LED Colour from state");

            ledStateActor ! LedStateRequest
            goto (InInteractiveModeAndWaitingForFunnelReply) using
              (KarotzClientData(interactiveId, restartRequested, currentLedColour, None))
          } else {
            log.info("notoverdue")
            pollFunnelWithNextCommand(currentLedColour)
          }
        }
        case None => pollFunnelWithNextCommand(currentLedColour)
      }
    }

    case Event(StopInteractiveMode, KarotzClientData(interactiveId, restartRequested, currentLedColour, permanentColourDeadline)) => {
      stopInteractiveMode(interactiveId);

      goto(WaitingForInteractiveModeToStop) using StartupData;
    }
  }


  when(InInteractiveModeAndWaitingForFunnelReply) {
    case Event(KarotzMessage(action), KarotzClientData(interactiveId, restartRequested, currentLedColour, permanentColourDeadline)) => {

      val (newLedColour: Option[LedColour], newPermanentColourDeadline: Option[Deadline]) = action match {
        case pulse:LightPulseAction => (Some(pulse.ledColour), Some((pulse.pulse).milliseconds.fromNow))
        case light:LightAction => (light.ledColour, None)
        case _ => (currentLedColour, permanentColourDeadline)
      }

      log.info("KarotzMessage received. new LED={}, new Deadline={}", newLedColour, newPermanentColourDeadline);

      performAction(action, interactiveId);

      goto(InInteractiveModeAndWaitingForKarotzReply) using KarotzClientData(interactiveId, restartRequested, newLedColour, newPermanentColourDeadline)

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

  when(InInteractiveModeAndWaitingForKarotzReply) {
    case Event(KarotzMessageProcessed, KarotzClientData(interactiveId, restartRequested, currentLedColour, permanentColourDeadline)) => {
      if (restartRequested) {
        stopInteractiveMode(interactiveId)
        goto(WaitingForInteractiveModeToStop) using StartupData
      } else {
        goto(InInteractiveModeAndWaitingForFunnelPoll)
      }
    }
  }

  private def startInteractiveMode():Unit = {
    val random = new Random(System.currentTimeMillis());

    val params = HashMap("apikey" -> karotzConfig.apiKey, "installid" -> karotzConfig.installId,
      "once" -> String.valueOf(random.nextInt(99999999)),
        "timestamp" -> (System.currentTimeMillis() / 1000L).toInt.toString);
    val url = getSignedUrl(params, karotzConfig.secretKey);

    log.info("quer is "+url);




    val xmlResultFuture: Future[NodeSeq] = pipeline(Get(url));

    val successResultFuture: Future[String] = xmlResultFuture.map(xml => {
      val interactiveId = xml \\ "interactiveId" text;
      if (interactiveId.isEmpty) {
        log.info(xml.toString());
        "";
      } else {
        interactiveId;
      }

    });

    successResultFuture onSuccess {
      case interactiveId: String => {
        if (interactiveId.isEmpty) {
          self ! InteractiveModeFailedToStart
        } else {
          self ! InteractiveModeStarted(interactiveId)

        }
      }
      case _ => log.info("hmm")
    }

    successResultFuture onFailure {
      case e:UnsuccessfulResponseException => {
        log.error("Error connecting to Karotz. Are you sure Bunny is turned on?")
        self ! InteractiveModeFailedToStart
      }
      case e:AskTimeoutException => {
        log.error("Karotz Start query timed out after [{}]", timeout);
        self ! InteractiveModeFailedToStart
      }
      case e: Exception => {
        log.error("Unknown Exception starting Karotz")
        e.printStackTrace()
        self ! InteractiveModeFailedToStart
      }
    }
  }

  private def stopInteractiveMode(interactiveId: String):Unit = {

    val params = HashMap("action" -> "stop",
      "interactiveid" -> interactiveId);

    val q: String = KarotzClient.KAROTZ_URL_INTERACTIVE_MODE + '?' + UrlUtils.buildQuery(params)


    log.info("quer is "+q);


    val xmlResultFuture: Future[NodeSeq] = pipeline(Get(q));
    val successResultFuture: Future[String] =  xmlResultFuture.map(xml => {
      val interactiveId = xml \\ "code" text;
      if (interactiveId.isEmpty) {
        log.info(xml.toString());
        "";
      } else {
        interactiveId;
      }

    });


    successResultFuture onSuccess {
      case interactiveId: String => {
        if (interactiveId.isEmpty) {
          log.info("hmm2")
        }
        self ! InteractiveModeStopped
      }
      case _ => log.info("hmm")
    }

    successResultFuture onFailure {
      case e:UnsuccessfulResponseException => {
        log.error("Error connecting to Karotz. Are you sure Bunny is turned on?")
      }
      case e:AskTimeoutException => {
        log.error("Stop query timed out after {}", timeout);
      }
      case e: Exception => {
        log.error("Unknown Exception stopping Karotz")
        e.printStackTrace()
      }
    }
  }

  private def getSignedUrl(params: Map[String, String], secretKey: String): String = {
    val q: String = UrlUtils.buildQuery(params)
    val signedQuery: String = UrlUtils.doHmacSha1(secretKey, q)
    log.info("signedQuery: [{}]", signedQuery)

    String.format("%s?%s&signature=%s", KarotzClient.KAROTZ_URL_START, q, URLEncoder.encode(signedQuery, "UTF-8"))
  }

  private def performAction(action: KarotzAction, interactiveId: String):Unit = {
    val q:String = UrlUtils.buildQuery(action.getParams + ("interactiveid" -> interactiveId));
    val url = "/api/karotz/" + action.getAPISuffix + '?' + q;

    log.info("query is "+url);
    val xmlResultFuture: Future[NodeSeq] = pipeline(Get(url));
    val successResultFuture: Future[String] = xmlResultFuture.map(_ \\ "code" text);



    successResultFuture onSuccess {
      case response => {
        log.info("Reply for query [{}] received", url);
        self ! KarotzMessageProcessed
      };
    }
    successResultFuture onFailure {
      case e:UnsuccessfulResponseException => {
        log.error("Karotz API call failed: {}. Will re-attempt API Call {}.", e.responseStatus.formatPretty, action)
        self ! KarotzMessage(action)
      }
      case e:AskTimeoutException => {
        log.error("Query [{}] timed out after {}.  Will re-attempt API Call {}.", url, timeout, action)
        self ! KarotzMessage(action)
      }
      case e:Exception => {
        log.error("Unknown Exception thrown by query [{}].  Will re-attempt API Call {}.", url, action)
        self ! KarotzMessage(action)
      }
    }
  }

  whenUnhandled {

    case Event(ShutdownRequest, KarotzClientData(interactiveId, restartRequested, currentLedColour, permanentColourDeadline)) => {
      stopInteractiveMode(interactiveId);
      goto(WaitingForInteractiveModeToStop) using ShutdownData(sender);
    }
    case Event(ShutdownRequest, data) => {
      sender ! ShutdownComplete;
      goto(KarotzShutdownCompleted);
    }
  }


  onTransition {
    case _ -> InInteractiveModeAndWaitingForFunnelPoll => {

      nextStateData match {
        case KarotzClientData(interactiveId, restartRequested, currentLedColour, permanentColourDeadline) => {
          if (restartRequested) {
            stopInteractiveMode(interactiveId);
          } else {
            context.system.scheduler.scheduleOnce(3 seconds, self, PollForMessages);
          }
        }
        case _ => throw new IllegalStateException("No Data");
      }
    }
  }
}
