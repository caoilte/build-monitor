package actors.messaging

import akka.actor.{ActorLogging, ActorRef, Actor}
import akka.util.duration._
import akka.util.Timeout
import akka.pattern.ask
import actors.messaging.NameGeneratingActor.{NamesStringReply, NamesStringRequest}
import akka.actor.FSM.Transition
import actors.BuildStateActor._
import actors.PrioritisedMessageFunnel.{LowPriorityMessage, HighPriorityMessage}
import actors.messaging.NameGeneratingActor.NamesStringRequest
import actors.BuildStateActor.BuildStateData
import actors.messaging.NameGeneratingActor.NamesStringReply
import actors.PrioritisedMessageFunnel.HighPriorityMessage
import actors.PrioritisedMessageFunnel.LowPriorityMessage
import actors.karotz.Karotz._

object MessageGeneratingActor {
  case class FormatMessage(formatMessage: String, args: String*)
}

class MessageGeneratingActor(namingActor: ActorRef, funnel: ActorRef) extends Actor with ActorLogging {

  implicit val timeout = Timeout(5 seconds)

  protected def receive = {
    case BuildStateNotification(JustFixed, BuildStateData(buildInformation, committers)) => {
      namingActor ? NamesStringRequest(committers.lastBuild) map {
        case NamesStringReply(authors) => {
          funnel forward HighPriorityMessage(KarotzMessage(SpeechAction("Attention. The "+buildInformation.jobName+" build has been fixed by "+authors)))
        }
      }
    }

    case BuildStateNotification(JustBroken, BuildStateData(buildInformation, committers)) => {
      namingActor ? NamesStringRequest(committers.whoBrokeBuild) map {
        case NamesStringReply(authors) => {
          funnel forward HighPriorityMessage(KarotzMessage(SpeechAction("Attention. The "+buildInformation.jobName+" build has been broken by "+authors)))
        }
      }
    }


    case BuildStateNotification(StillBroken, BuildStateData(buildInformation, committers)) => {
      val breakageAuthorsStringFuture = ask(namingActor, NamesStringRequest(committers.whoBrokeBuild))
      val sinceBreakageAuthorsStringFuture = ask(namingActor, NamesStringRequest(committers.sincePreviousGoodBuild))


      val buildsSinceLastStateChange = buildInformation.lastBuildNumber - buildInformation.lastSuccessfulBuildNumber


      for {
        NamesStringReply(breakageAuthorsString) <- breakageAuthorsStringFuture.mapTo[NamesStringReply]
        NamesStringReply(sinceBreakageAuthorsString) <- sinceBreakageAuthorsStringFuture.mapTo[NamesStringReply]
      } yield funnel forward LowPriorityMessage(KarotzMessage(SpeechAction(
        "Attention. The "+buildInformation.jobName+" build was broken by " + breakageAuthorsString + " and has failed "+ buildsSinceLastStateChange + " times since with " +
          "checkins from " + sinceBreakageAuthorsString)))
    }
  }
}
