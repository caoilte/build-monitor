package actors.messaging

import akka.actor.{ActorLogging, ActorRef, Actor}

import scala.concurrent.Await
import scala.concurrent.duration._
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
import collection.immutable.HashSet
import akka.util.Timeout

object MessageGeneratingActor {
  case class FormatMessage(formatMessage: String, args: String*)
}

class MessageGeneratingActor(namingActor: ActorRef, funnel: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher

  implicit val timeout = Timeout(5 seconds)

  private def justFixedMessage(triggeredManually: Boolean, jobName: String, authors: String) = {
    if (triggeredManually) {
      "Attention. The "+jobName+" build has been fixed after being manually triggered by "+authors
    } else {
      "Attention. The "+jobName+" build has been fixed by "+authors
    }
  }

  private def justBrokenMessage(triggeredManually: Boolean, jobName: String, authors: String) = {
    if (triggeredManually) {
      "Attention. The "+jobName+" build broke after being manually triggered by "+authors
    } else {
      "Attention. The "+jobName+" build has been broken by "+authors
    }
  }

  private def stillBrokenMessage(triggeredManually: Boolean, jobName: String, breakageAuthors: String,
                                 buildsSinceLastStateChange: Int, sinceBreakageAuthors: String) = {
    if (triggeredManually) {
      "Attention. The "+jobName+" build was broken by " + breakageAuthors+ " and has failed "+ buildsSinceLastStateChange + " times since. The most " +
        "recent build failure was manually triggered by " + sinceBreakageAuthors
    } else {
      "Attention. The "+jobName+" build was broken by " + breakageAuthors+ " and has failed "+ buildsSinceLastStateChange + " times since with " +
        "checkins from " + sinceBreakageAuthors
    }
  }



  override def receive = {
    case BuildStateNotification(JustFixed, BuildStateData(triggeredManually, buildInformation, committers)) => {
      namingActor ? NamesStringRequest(committers.lastBuild) map {
        case NamesStringReply(authors) => {
          funnel forward HighPriorityMessage(KarotzMessage(SpeechAction(justFixedMessage(triggeredManually, buildInformation.jobName, authors))))
        }
      }
    }

    case BuildStateNotification(JustBroken, BuildStateData(triggeredManually, buildInformation, committers)) => {
      namingActor ? NamesStringRequest(committers.whoBrokeBuild) map {
        case NamesStringReply(authors) => {
          funnel forward HighPriorityMessage(KarotzMessage(SpeechAction(justBrokenMessage(triggeredManually, buildInformation.jobName, authors))))
        }
      }
    }


    case BuildStateNotification(StillBroken, BuildStateData(triggeredManually, buildInformation, committers)) => {
      val breakageAuthorsStringFuture = ask(namingActor, NamesStringRequest(committers.whoBrokeBuild))
      val sinceBreakageAuthorsStringFuture = ask(namingActor, NamesStringRequest(committers.sincePreviousGoodBuild))


      val buildsSinceLastStateChange = buildInformation.lastBuildNumber - buildInformation.lastSuccessfulBuildNumber


      for {
        NamesStringReply(breakageAuthorsString) <- breakageAuthorsStringFuture.mapTo[NamesStringReply]
        NamesStringReply(sinceBreakageAuthorsString) <- sinceBreakageAuthorsStringFuture.mapTo[NamesStringReply]
      } yield funnel forward LowPriorityMessage(KarotzMessage(SpeechAction(
        stillBrokenMessage(triggeredManually, buildInformation.jobName, breakageAuthorsString,
          buildsSinceLastStateChange, sinceBreakageAuthorsString))))
    }

    case BuildStateNotification(Healthy, BuildStateData(triggeredManually, buildInformation, committers)) => {
      if (triggeredManually) {
        namingActor ? NamesStringRequest(committers.lastBuild) map {
          case NamesStringReply(authors) => {
            funnel forward LowPriorityMessage(KarotzMessage(SpeechAction(
              buildInformation.jobName+" succeeded after being manually triggered by "+authors
            )))
          }
        }

      }
    }
  }
}
