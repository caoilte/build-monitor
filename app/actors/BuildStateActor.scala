package actors

import akka.actor.{ Actor, ActorRef, FSM }
import actors.BuildStateActor._
import collection.immutable.HashSet


object BuildStateActor {
  // received events

  abstract class BuildStateMessage
  case class BuildSucceeded(jobName: String, buildsSinceLastFailure: Int, authors: HashSet[String]) extends BuildStateMessage
  case class BuildFailed(jobName: String, buildsSinceLastSuccess: Int, authors: HashSet[String]) extends BuildStateMessage
  case class SubscribeToStateDataChanges(actorRef: ActorRef) extends BuildStateMessage

  // sent events

  case class BuildStateNotification(state: State, data: BuildStateData)

  // states
  sealed trait State {
    def isWorking: Boolean;

  }
  case object Unknown extends State {
    override def isWorking = throw new UnsupportedOperationException();
  }
  case object Healthy extends State {
    override def isWorking = true;
  }
  case object JustFixed extends State {
    override def isWorking = true;

  }
  case object JustBroken extends State {
    override def isWorking = false;

  }
  case object StillBroken extends State {
    override def isWorking = false;
  }

  sealed trait Data

  case object NoBuildStateData extends Data
  case class BuildStateData(jobName: String, buildsSinceLastStateChange: Int, stateChangeAuthors: Set[String], sinceStateChangeAuthors: Set[String]) extends Data {
    def this(jobName: String, breakageAuthors: Set[String]) {
      this(jobName, 1, breakageAuthors, new HashSet[String]());
    }
    def this(jobName: String, buildsSinceLastStateChange: Int) {
      this(jobName, buildsSinceLastStateChange, new HashSet[String](), new HashSet[String]());
    }
  }
}

class BuildStateActor extends Actor with FSM[State, Data] {

  var listeningActors = new HashSet[ActorRef]();

  startWith(Unknown, NoBuildStateData)

  when(Unknown) {
    case Event(BuildSucceeded(jobName, buildsSinceLastFailure, authors), NoBuildStateData) => {
        goto(Healthy) using new BuildStateData(jobName, buildsSinceLastFailure)
    }
    case Event(BuildFailed(jobName, buildsSinceLastSuccess, authors), NoBuildStateData) => {
      if (buildsSinceLastSuccess == 1) {
        goto(JustBroken) using new BuildStateData(jobName, authors)
      } else {
        goto(StillBroken) using new BuildStateData(jobName, buildsSinceLastSuccess, authors, new HashSet[String]())
      }
    }
  }


  when(JustFixed) {
    case Event(BuildSucceeded(jobName, buildsSinceLastFailure, authors), _) => {

      if (buildsSinceLastFailure > 1) {
        goto(Healthy) using new BuildStateData(jobName, buildsSinceLastFailure)
      } else {
        stay()
      }
    }
    case Event(BuildFailed(jobName, buildsSinceLastSuccess, authors), _) => {
      goto(JustBroken) using new BuildStateData(jobName, authors)
    }
  }

  when(Healthy) {
    case Event(BuildSucceeded(jobName, buildsSinceLastFailure, authors),
    BuildStateData(oldJobName, oldBuildsSinceLastFailure, _, _)) => {

      if (buildsSinceLastFailure > oldBuildsSinceLastFailure) {
        stay() using new BuildStateData(jobName, buildsSinceLastFailure)
      } else {
        stay()
      }
    }
    case Event(BuildFailed(jobName, buildsSinceLastSuccess, authors), _) => {
      goto(JustBroken) using new BuildStateData(jobName, authors)
    }
  }

  when(JustBroken) {
    case Event(BuildSucceeded(jobName, buildsSinceLastFailure, authors), _) => {
      goto(JustFixed) using new BuildStateData(jobName, authors)
    }
    case Event(BuildFailed(jobName, buildsSinceLastSuccess, authors),
    BuildStateData(oldJobName, oldBuildsSinceLastFailure, oldBreakageAuthors, oldSinceBreakageAuthors)) => {

      if (buildsSinceLastSuccess > 1) {
        goto(StillBroken) using new BuildStateData(jobName, buildsSinceLastSuccess, oldBreakageAuthors, authors)
      } else {
        stay() using new BuildStateData(jobName, authors)
      }
    }
  }

  when(StillBroken) {

    case Event(BuildSucceeded(jobName, buildsSinceLastFailure, authors), _) => {
      goto(JustFixed) using new BuildStateData(jobName, authors)
    }
    case Event(BuildFailed(jobName, buildsSinceLastSuccess, sinceBreakageAuthors),
    BuildStateData(oldJobName, oldBuildsSinceLastFailure, oldBreakageAuthors, oldSinceBreakageAuthors)) => {
      val data = new BuildStateData(jobName, buildsSinceLastSuccess, oldBreakageAuthors, oldSinceBreakageAuthors ++ sinceBreakageAuthors)

      if (buildsSinceLastSuccess > oldBuildsSinceLastFailure) {
        listeningActors.foreach(_ ! BuildStateNotification(StillBroken, data))
      }
      stay() using data
    }

  }

  whenUnhandled {

    case Event(SubscribeToStateDataChanges(actorRef), _) => {
      listeningActors += actorRef;
      stay()
    }

    case Event(e, s) =>
      log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }

  onTransition {
    case oldState -> newState => {
      nextStateData match {
        case bsd:BuildStateData => {
          listeningActors.foreach(_ ! BuildStateNotification(newState, bsd))
        }
      }

    }
  }

}
