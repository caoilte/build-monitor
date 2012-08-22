package actors

import akka.actor.{ActorLogging, FSM, Actor, ActorRef}
import collection.mutable
import com.sun.tools.javac.comp.Todo
import akka.event.LoggingReceive


object PrioritisedMessageFunnel {
  abstract class FunnelMessage
  case object NoMessagesQueued

  // received events
  abstract class PrioritisedMessage
  case class LowPriorityMessage(message: FunnelMessage) extends PrioritisedMessage
  case class HighPriorityMessage(message: FunnelMessage) extends PrioritisedMessage
  case object ReplyWithNextKarotzCommand

}


class PrioritisedMessageFunnel extends Actor with ActorLogging {
  import PrioritisedMessageFunnel._;

  var highPriorityCommands = new mutable.Queue[FunnelMessage];
  var lowPriorityCommands = new mutable.LinkedHashMap[(ActorRef, Class[_] ), FunnelMessage]

  protected def receive = LoggingReceive {
    case LowPriorityMessage(message) => {
      log.info("message = " +message)
      lowPriorityCommands.put((sender, message.getClass), message)
    };
    case HighPriorityMessage(message) => highPriorityCommands += message;
    case ReplyWithNextKarotzCommand => {
      if (!highPriorityCommands.isEmpty) {
        sender ! highPriorityCommands.dequeue();
      } else {
        lowPriorityCommands.headOption match {
          case Some(x) => removeAndReplyForLowPriorityCommandElement(x);
          case None => sender ! NoMessagesQueued
        };
      }
    }
  }

  private def removeAndReplyForLowPriorityCommandElement(element: ((ActorRef, Class[_]), FunnelMessage)) {
    sender ! element._2
    lowPriorityCommands.remove(element._1)
  }
}
