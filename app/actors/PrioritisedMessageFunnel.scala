package actors

import akka.actor.{ActorLogging, FSM, Actor, ActorRef}
import collection.mutable
import com.sun.tools.javac.comp.Todo
import akka.event.LoggingReceive
import karotz.KarotzClient._
import karotz.KarotzClient.KarotzMessage
import scala.Some


object PrioritisedMessageFunnel {
  abstract class FunnelMessage
  case object NoMessagesQueued

  // received events
  abstract class PrioritisedMessage
  case class LowPriorityMessage(message: FunnelMessage) extends PrioritisedMessage
  case class HighPriorityMessage(message: FunnelMessage) extends PrioritisedMessage
  case class ReplyWithNextKarotzCommand(existingLedState: Option[LedColour])

  trait FunnelMessageQueue {
    def headOption(): Option[FunnelMessage];
    def dequeueNextMessage(): FunnelMessage;
    def addMessage(sender: ActorRef, message: FunnelMessage);

    def handleRequestForNextMessage(sender: ActorRef, existingLedState: Option[LedColour]) {
      headOption() match {
        case Some(KarotzMessage(lightPulseAction:LightPulseAction)) => {
          existingLedState match {
            case None => {
              sender ! dequeueNextMessage()
            }
            case _ => sender ! KarotzMessage(LightAction(None))
          }
        }
        case None => sender ! NoMessagesQueued
        case _ => sender ! dequeueNextMessage()
      }
    }
  }

  class SingleMessageQueue extends FunnelMessageQueue {
    var queue = new mutable.Queue[FunnelMessage];

    def headOption() = queue.headOption
    def dequeueNextMessage() = queue.dequeue()

    def addMessage(sender: ActorRef, message: FunnelMessage) = queue += message;
  }

  class MappedMessageQueue extends FunnelMessageQueue {
    var queue = new mutable.LinkedHashMap[(ActorRef, Class[_] ), FunnelMessage]

    def headOption() = queue.headOption.map(_._2)

    def dequeueNextMessage() = {
      val nextMessage = queue.head;
      queue.remove(queue.head._1);
      nextMessage._2;
    }

    def addMessage(sender: ActorRef, message: FunnelMessage) = queue.put((sender, message.getClass), message)
  }

}


class PrioritisedMessageFunnel extends Actor with ActorLogging {
  import PrioritisedMessageFunnel._;

  var highPriorityCommands = new SingleMessageQueue();
  var lowPriorityCommands = new MappedMessageQueue();

  protected def receive = LoggingReceive {
    case LowPriorityMessage(message) => lowPriorityCommands.addMessage(sender, message)
    case HighPriorityMessage(message) => highPriorityCommands.addMessage(sender, message)
    case ReplyWithNextKarotzCommand(existingLedState) => {
      if (!highPriorityCommands.headOption().isEmpty) {
        highPriorityCommands.handleRequestForNextMessage(sender, existingLedState)
      } else {
        lowPriorityCommands.handleRequestForNextMessage(sender, existingLedState)
      }
    }
  }
}
