package actors.messaging

import akka.actor.{ActorLogging, ActorRef, Actor}
import akka.util.duration._
import akka.util.Timeout
import akka.pattern.ask
import actors.messaging.NameGeneratingActor.{NamesStringReply, NamesStringRequest}
import actors.karotz.KarotzClientAdaptor.SpeechActionMessage
import akka.actor.FSM.Transition
import actors.BuildStateActor.{StillBroken, JustFixed, JustBroken, BuildStateData}
import actors.PrioritisedMessageFunnel.{LowPriorityMessage, HighPriorityMessage}

object MessageGeneratingActor {
  case class FormatMessage(formatMessage: String, args: String*)
}

class MessageGeneratingActor(namingActor: ActorRef, funnel: ActorRef) extends Actor with ActorLogging {

  implicit val timeout = Timeout(5 seconds)

  protected def receive = {
    case (JustFixed, BuildStateData(jobName, buildsSinceLastStateChange, fixAuthors, sinceFixAuthors)) => {
      namingActor ? NamesStringRequest(fixAuthors) map {
        case NamesStringReply(authors) => {
          funnel forward HighPriorityMessage(SpeechActionMessage("Attention. The "+jobName+" build has been fixed by "+authors))
        }
      }
    }

    case (JustBroken, BuildStateData(jobName, buildsSinceLastStateChange, breakageAuthors, sinceBreakageAuthors)) => {
      namingActor ? NamesStringRequest(breakageAuthors) map {
        case NamesStringReply(authors) => {
          funnel forward HighPriorityMessage(SpeechActionMessage("Attention. The "+jobName+" build has been broken by "+authors))
        }
      }
    }


    case (StillBroken, BuildStateData(jobName, buildsSinceLastStateChange, breakageAuthors, sinceBreakageAuthors)) => {
      val breakageAuthorsStringFuture = ask(namingActor, NamesStringRequest(breakageAuthors))
      val sinceBreakageAuthorsStringFuture = ask(namingActor, NamesStringRequest(sinceBreakageAuthors))


      for {
        NamesStringReply(breakageAuthorsString) <- breakageAuthorsStringFuture.mapTo[NamesStringReply]
        NamesStringReply(sinceBreakageAuthorsString) <- sinceBreakageAuthorsStringFuture.mapTo[NamesStringReply]
      } yield funnel forward LowPriorityMessage(SpeechActionMessage(
        "Attention. The "+jobName+" build was broken by " + breakageAuthorsString + " and has failed "+ buildsSinceLastStateChange + " times since with " +
          "checkins from " + sinceBreakageAuthorsString))

    }
  }
}
