package actors

import akka.actor.{ Actor, ActorRef, FSM }
import actors.BuildStateActor._
import collection.immutable.HashSet
import actors.BuildStatusMonitoringActor.RegisterStatusMonitoring


object BuildStateActor {
  // received events

  abstract class BuildStateMessage
  case class BuildInformation(jobName: String, lastBuildNumber: Int, lastSuccessfulBuildNumber: Int, lastFailedBuildNumber: Int) extends BuildStateMessage {
    def updateWithSuccessfulBuildNumber(successfulBuildNumber: Int) = BuildInformation(jobName, successfulBuildNumber, successfulBuildNumber, lastFailedBuildNumber)
    def updateWithFailedBuildNumber(failedBuildNumber: Int) = BuildInformation(jobName, failedBuildNumber, lastSuccessfulBuildNumber, failedBuildNumber)
  }
  case class BuildSucceeded(buildNumber: Int, committersThisBuild: HashSet[String], committersSincePreviousGoodBuild: HashSet[String]) extends BuildStateMessage
  case class BuildFailed(buildNumber: Int, committersThisBuild: HashSet[String], committersSincePreviousGoodBuild: HashSet[String]) extends BuildStateMessage
  case class SubscribeToStateDataChanges(actorRef: ActorRef) extends BuildStateMessage

  case class Committers(lastBuild: Set[String], sincePreviousGoodBuild: Set[String], whoBrokeBuild: Set[String]) {
    def this () = this(new HashSet[String], new HashSet[String], new HashSet[String])
    def this (lastBuild: Set[String]) = this(lastBuild, new HashSet[String], new HashSet[String])
    def this (lastBuild: Set[String], whoBrokeBuild: Set[String]) = this(lastBuild, new HashSet[String], whoBrokeBuild)
    def updateWithMoreCommitters(committersThisBuild: HashSet[String], committersSincePreviousGoodBuild: HashSet[String]):Committers = {
      new Committers(committersThisBuild, committersThisBuild ++ sincePreviousGoodBuild, whoBrokeBuild)
    }
  }

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

  sealed trait Data {
    def addBuildInformation(buildInformation: BuildInformation): Data
  }

  case object NoBuildStateData extends Data {
    override def addBuildInformation(buildInformation: BuildInformation): Data = new BuildStateData(buildInformation)
  }
  case class BuildStateData(buildInformation: BuildInformation, committers: Committers) extends Data {
      def this(buildInformation: BuildInformation) {
        this(buildInformation, new Committers());
      }

    def addBuildInformation(buildInformation: BuildInformation) = new BuildStateData(buildInformation, committers);
  }
}

class BuildStateActor extends Actor with FSM[State, Data] {

  var listeningActors = new HashSet[ActorRef]();

//  override def preStart() {
//    // registering with other actors
//    buildStateActor ! RegisterStatusMonitoring(self)
//  }

  startWith(Unknown, NoBuildStateData)

  when(Unknown) {
    case Event(BuildSucceeded(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      val newBuildState = BuildStateData(buildInformation, new Committers(committersLastBuild, committersSincePreviousGoodBuild, committersSincePreviousGoodBuild))
      if (buildInformation.lastBuildNumber == buildInformation.lastFailedBuildNumber + 1) {
        log.info("Build state initialised with last build number '{}' and status 'Just Fixed'", buildInformation.lastBuildNumber)
        goto(JustFixed) using newBuildState
      } else {
        log.info("Build state initialised with last build number '{}' and status 'Healthy'", buildInformation.lastBuildNumber)
        goto(Healthy) using newBuildState
      }
    }
    case Event(BuildFailed(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      val newBuildState = BuildStateData(buildInformation, new Committers(committersLastBuild, committersSincePreviousGoodBuild, committersSincePreviousGoodBuild))
      if (buildInformation.lastBuildNumber == buildInformation.lastSuccessfulBuildNumber + 1) {
        log.info("Build state initialised with last build number '{}' and status 'Just Broken'", buildInformation.lastBuildNumber)
        goto(JustBroken) using newBuildState
      } else {
        log.info("Build state initialised with last build number '{}' and status 'Still Broken'", buildInformation.lastBuildNumber)
        goto(StillBroken) using newBuildState
      }
    }
  }


  when(JustFixed) {
    case Event(BuildSucceeded(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      if (buildNumber > buildInformation.lastBuildNumber) {
        val newBuildState = BuildStateData(buildInformation.updateWithFailedBuildNumber(buildNumber), new Committers(committersLastBuild))
        log.info("Build state updated from 'Just Fixed' to 'Healthy' and new build number '{}'", buildNumber)
        goto(Healthy) using newBuildState
      } else stay()
    }
    case Event(BuildFailed(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      val newBuildState = BuildStateData(buildInformation.updateWithFailedBuildNumber(buildNumber),
        new Committers(committersLastBuild, committersSincePreviousGoodBuild))
      log.info("Build state updated from 'Just Fixed' to 'Just Broken' and new build number '{}'", buildNumber)
      goto(JustBroken) using newBuildState
    }
  }

  when(Healthy) {
    case Event(BuildSucceeded(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      if (buildNumber > buildInformation.lastBuildNumber) {
        val newBuildState = BuildStateData(buildInformation.updateWithFailedBuildNumber(buildNumber), new Committers(committersSincePreviousGoodBuild))
        notifyListeners(Healthy, newBuildState)
        log.info("Build state 'Healthy' updated with new build number '{}'", buildNumber)
        stay() using newBuildState
      } else {
        stay()
      }
    }
    case Event(BuildFailed(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      val newBuildState = BuildStateData(buildInformation.updateWithFailedBuildNumber(buildNumber),
        new Committers(committersLastBuild, committersSincePreviousGoodBuild))
      log.info("Build state updated from 'Healthy' to 'Just Broken' and new build number '{}'", buildNumber)
      goto(JustBroken) using newBuildState
    }
  }

  when(JustBroken) {
    case Event(BuildSucceeded(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      val newBuildState = BuildStateData(buildInformation.updateWithFailedBuildNumber(buildNumber),
        committers.updateWithMoreCommitters(committersLastBuild, committersSincePreviousGoodBuild))
      log.info("Build state updated from 'Just Broken' to 'Healthy' and new build number '{}'", buildNumber)
      goto(JustFixed) using newBuildState
    }
    case Event(BuildFailed(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      if (buildNumber > buildInformation.lastBuildNumber) {
        val newBuildState = BuildStateData(buildInformation.updateWithFailedBuildNumber(buildNumber),
          committers.updateWithMoreCommitters(committersLastBuild, committersSincePreviousGoodBuild))
        log.info("Build state updated from 'Just Broken' to 'Still Broken' and new build number '{}'", buildNumber)
        goto(StillBroken) using newBuildState
      } else stay()
    }
  }


  when(StillBroken) {
    case Event(BuildSucceeded(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      val newBuildState = BuildStateData(buildInformation.updateWithFailedBuildNumber(buildNumber),
        committers.updateWithMoreCommitters(committersLastBuild, committersSincePreviousGoodBuild))
      log.info("Build state updated from 'Still Broken' to 'Just Fixed' and new build number '{}'", buildNumber)
      goto(JustFixed) using newBuildState
    }
    case Event(BuildFailed(buildNumber, committersLastBuild, committersSincePreviousGoodBuild),
    BuildStateData(buildInformation, committers)) => {
      if (buildNumber > buildInformation.lastBuildNumber) {
        val newBuildState = BuildStateData(buildInformation.updateWithFailedBuildNumber(buildNumber),
          committers.updateWithMoreCommitters(committersLastBuild, committersSincePreviousGoodBuild))
        notifyListeners(StillBroken, newBuildState)
        log.info("Build state 'Still Broken' updated with new build number '{}'", buildNumber)
        stay() using newBuildState
      } else {
        stay()
      }
    }
  }

  whenUnhandled {

    case Event(buildInformation: BuildInformation, data) => {
      stay() using data.addBuildInformation(buildInformation)
    }

    case Event(SubscribeToStateDataChanges(actorRef), _) => {
      listeningActors += actorRef;
      stay()
    }

    case Event(e, s) =>
      log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }

  def notifyListeners(nextState: BuildStateActor.State, nextStateData: Data) = nextStateData match {
      case bsd:BuildStateData => {
        listeningActors.foreach(_ ! BuildStateNotification(nextState, bsd))
      }
    }

  onTransition {
    case oldState -> newState => notifyListeners(newState, nextStateData)
  }

}
