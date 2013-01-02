package actors

import akka.actor.{ Actor, ActorRef, FSM }
import actors.BuildStateActor._
import collection.immutable.HashSet


object BuildStateActor {
  // received events

  abstract class BuildStateMessage
  case class BuildInformation(jobName: String, lastBuildNumber: Int, lastSuccessfulBuildNumber: Int = 0, lastFailedBuildNumberOpt: Option[Int]) extends BuildStateMessage {
    def lastFailedBuildNumber = lastFailedBuildNumberOpt.getOrElse(0)
    def updateWithSuccessfulBuildNumber(successfulBuildNumber: Int) = BuildInformation(jobName, successfulBuildNumber, successfulBuildNumber, lastFailedBuildNumberOpt)
    def updateWithFailedBuildNumber(failedBuildNumber: Int) = BuildInformation(jobName, failedBuildNumber, lastSuccessfulBuildNumber, lastFailedBuildNumberOpt)

    def isJustFixed = {
      lastBuildNumber == lastSuccessfulBuildNumber &&
        lastBuildNumber == lastFailedBuildNumber + 1
    }
    def isJustBroken = {
      lastBuildNumber == lastFailedBuildNumber &&
        lastBuildNumber ==  lastSuccessfulBuildNumber + 1
    }
  }
  case class BuildDetails(triggeredManually: Boolean, buildNumber: Int,
                          committersThisBuild: HashSet[String], committersSincePreviousGoodBuild: HashSet[String])
  case class BuildSucceeded(details: BuildDetails) extends BuildStateMessage
  case class BuildFailed(details: BuildDetails) extends BuildStateMessage
  case class SubscribeToStateDataChanges(actorRef: ActorRef) extends BuildStateMessage

  case class Committers(lastBuild: Set[String], sincePreviousGoodBuild: Set[String], whoBrokeBuild: Set[String]) {
    def this () = this(new HashSet[String], new HashSet[String], new HashSet[String])
    def this (lastBuild: Set[String]) = this(lastBuild, new HashSet[String], new HashSet[String])
    def this (lastBuild: Set[String], whoBrokeBuild: Set[String]) = this(lastBuild, new HashSet[String], whoBrokeBuild)
    def this (details: BuildDetails) = this(details.committersThisBuild, details.committersSincePreviousGoodBuild)
    def updateWithMoreCommitters(details: BuildDetails):Committers = {
      new Committers(details.committersThisBuild, details.committersThisBuild ++ sincePreviousGoodBuild, whoBrokeBuild)
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
  case class BuildStateData(triggeredManually: Boolean, buildInformation: BuildInformation, committers: Committers) extends Data {

    def this(buildInformation: BuildInformation) {
      this(false, buildInformation, new Committers());
    }

    def addBuildInformation(buildInformation: BuildInformation) = {
      copy(buildInformation = buildInformation)
    }

    def isJustFixed = buildInformation.isJustFixed
    def isJustBroken = buildInformation.isJustBroken
    def isOlderThan(buildDetails: BuildDetails) = buildDetails.buildNumber > buildInformation.lastBuildNumber
    def updateWithSuccess(buildDetails: BuildDetails) = {
      val newBuildInformation = buildInformation.updateWithSuccessfulBuildNumber(buildDetails.buildNumber)
      if (newBuildInformation.isJustFixed) {
        BuildStateData(buildDetails.triggeredManually, newBuildInformation, committers.updateWithMoreCommitters(buildDetails))
      }
      else {
        BuildStateData(buildDetails.triggeredManually, newBuildInformation, new Committers(buildDetails))
      }
    }
    def updateWithFailure(buildDetails: BuildDetails) = {
      val newBuildInformation = buildInformation.updateWithFailedBuildNumber(buildDetails.buildNumber)
      if (newBuildInformation.isJustBroken) {
        BuildStateData(buildDetails.triggeredManually, newBuildInformation, new Committers(buildDetails))
      }
      else {
        BuildStateData(buildDetails.triggeredManually, newBuildInformation, committers.updateWithMoreCommitters(buildDetails))
      }
    }
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
    case Event(BuildSucceeded(details), buildStateData:BuildStateData) => {
      val newBuildState = buildStateData.copy(triggeredManually = details.triggeredManually,
        committers = new Committers(details.committersThisBuild,
        details.committersSincePreviousGoodBuild, details.committersSincePreviousGoodBuild))
      if (buildStateData.isJustFixed) {
        log.info("Build state initialised with last build number '{}' and status 'Just Fixed'", buildStateData.buildInformation.lastBuildNumber)
        goto(JustFixed) using newBuildState
      } else {
        log.info("Build state initialised with last build number '{}' and status 'Healthy'", buildStateData.buildInformation.lastBuildNumber)
        goto(Healthy) using newBuildState
      }
    }
    case Event(BuildFailed(details), buildStateData:BuildStateData) => {
      val newBuildState = buildStateData.copy(triggeredManually = details.triggeredManually,
        committers = new Committers(details.committersThisBuild,
        details.committersSincePreviousGoodBuild, details.committersSincePreviousGoodBuild))
      if (buildStateData.isJustBroken) {
        log.info("Build state initialised with last build number '{}' and status 'Just Broken'", buildStateData.buildInformation.lastBuildNumber)
        goto(JustBroken) using newBuildState
      } else {
        log.info("Build state initialised with last build number '{}' and status 'Still Broken'", buildStateData.buildInformation.lastBuildNumber)
        goto(StillBroken) using newBuildState
      }
    }
  }


  when(JustFixed) {
    case Event(BuildSucceeded(details), buildStateData:BuildStateData) => {
      if (buildStateData.isOlderThan(details)) {
        log.info("Build state updated from 'Just Fixed' to 'Healthy' and new build number '{}'", details.buildNumber)
        goto(Healthy) using buildStateData.updateWithSuccess(details)
      } else stay()
    }
    case Event(BuildFailed(details), buildStateData:BuildStateData) => {
      log.info("Build state updated from 'Just Fixed' to 'Just Broken' and new build number '{}'", details.buildNumber)
      goto(JustBroken) using buildStateData.updateWithFailure(details)
    }
  }

  when(Healthy) {
    case Event(BuildSucceeded(details), buildStateData:BuildStateData) => {
      if (buildStateData.isOlderThan(details)) {
        val newBuildState = buildStateData.updateWithSuccess(details)
        notifyListeners(Healthy, newBuildState)
        log.info("Build state 'Healthy' updated with new build number '{}'", details.buildNumber)
        stay() using newBuildState
      } else {
        stay()
      }
    }
    case Event(BuildFailed(details), buildStateData:BuildStateData) => {
      log.info("Build state updated from 'Healthy' to 'Just Broken' and new build number '{}'", details.buildNumber)
      goto(JustBroken) using buildStateData.updateWithFailure(details)
    }
  }

  when(JustBroken) {
    case Event(BuildSucceeded(details), buildStateData:BuildStateData) => {
      log.info("Build state updated from 'Just Broken' to 'Healthy' and new build number '{}'", details.buildNumber)
      goto(JustFixed) using buildStateData.updateWithSuccess(details)
    }
    case Event(BuildFailed(details), buildStateData:BuildStateData) => {
      if (buildStateData.isOlderThan(details)) {
        log.info("Build state updated from 'Just Broken' to 'Still Broken' and new build number '{}'", details.buildNumber)
        goto(StillBroken) using buildStateData.updateWithFailure(details)
      } else stay()
    }
  }


  when(StillBroken) {
    case Event(BuildSucceeded(details), buildStateData:BuildStateData) => {
      log.info("Build state updated from 'Still Broken' to 'Just Fixed' and new build number '{}'", details.buildNumber)
      goto(JustFixed) using buildStateData.updateWithSuccess(details)
    }
    case Event(BuildFailed(details), buildStateData:BuildStateData) => {
      if (buildStateData.isOlderThan(details)) {
        val newBuildState = buildStateData.updateWithFailure(details)
        notifyListeners(StillBroken, newBuildState)
        log.info("Build state 'Still Broken' updated with new build number '{}'", details.buildNumber)
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
      case NoBuildStateData => log.info("No build data in new state so will not notify listeners of change")
    }

  onTransition {
    case oldState -> newState => notifyListeners(newState, nextStateData)
  }

}
