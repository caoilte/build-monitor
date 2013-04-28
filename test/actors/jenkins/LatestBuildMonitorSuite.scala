package actors.jenkins

import config.IJobConfig
import play.api.libs.json.Json
import org.specs2.mutable._
import org.specs2.matcher.ThrownMessages

//import net.liftweb.json._
import scala.None
import collection.immutable.HashSet
import actors.BuildStateActor.{BuildSucceeded, BuildFailed}

object LatestBuildMonitorSuite {
  val latestBuildMonitor = new LatestBuildMonitor(new IJobConfig {
    val name = "test"
  })

  val notBuildingJson =
    Json.parse("""{
        "building" : true
       }""")

  val failingBuild =
    Json.parse(
      """{
           "actions" : [
             {
               "causes" : [
                 {
                  "shortDescription" : "Started by an SCM change",
                  "userName" : "caoilte"
                 }
               ]
             }
           ],
           "building" : false,
           "number" : 5,
           "result" : "FAILURE",
           "changeSet" : {
              "items" : [
              {
                "author" : { "fullName" : "caoilte" }
              },
              {
                "author" : { "fullName" : "ben" }
              }
              ]
           },
           "culprits" : [
              { "fullName" : "caoilte" },
              { "fullName" : "ben" },
              { "fullName" : "bruce" }
           ],
           "timestamp" : 999
         }
      """)

  val passingBuild =
    Json.parse(
      """{
           "actions" : [
              {
                "causes" : [
                  {
                    "shortDescription" : "Started by an SCM change",
                    "userName" : "caoilte"
                  }
                ]
              }
           ],
           "building" : false,
           "number" : 3,
           "result" : "SUCCESS",
           "changeSet" : {
              "items" : [
              {
                "author" : { "fullName" : "caoilte" }
              }
              ]
           },
           "culprits" : [
              { "fullName" : "caoilte" },
              { "fullName" : "ben" },
              { "fullName" : "bruce" }
           ],
           "timestamp" : 999
         }
      """)



}

class LatestBuildMonitorSuite extends Specification with ThrownMessages {
  import LatestBuildMonitorSuite._

  // if all aok culprit and changeset include person who did commit



  "The transformation of a 'not building' query response" should {
    "be None" in {
      latestBuildMonitor.transformQueryResponse(notBuildingJson) must beNone
    }
  }

  // asked to transform a failed build with two committers and a history of three committers

  def failingBuildState = latestBuildMonitor.transformQueryResponse(failingBuild).asInstanceOf[Option[BuildFailed]] match {
    case None => fail("Failed to Transform query response")
    case Some(buildState) => buildState
  }


  "The transformation of a 'failed build' query response" should {
    "correctly return the build number" in {
      failingBuildState.details.buildNumber must_== 5
    }
    "return the most recent committers as a hashset" in {
      failingBuildState.details.committersThisBuild must_== new HashSet() + "caoilte" + "ben"
    }
    "return the committers since the previous good build as a hashset" in {
      failingBuildState.details.committersSincePreviousGoodBuild must_== new HashSet() + "caoilte" + "ben" + "bruce"
    }
  }

  def passingBuildState = latestBuildMonitor.transformQueryResponse(passingBuild).asInstanceOf[Option[BuildSucceeded]] match {
    case None => fail("Failed to Transform query response")
    case Some(buildState) => buildState
  }

  "The transformation of a 'passing build' query response with one committer" should {
    "correctly return the build number" in {
      passingBuildState.details.buildNumber must_== 3
    }
    "return the most recent committers as a hashset" in {
      passingBuildState.details.committersThisBuild must_== new HashSet() + "caoilte"
    }
  }

}
