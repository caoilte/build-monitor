package actors.jenkins

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import config.IJobConfig
import net.liftweb.json._
import scala.None
import collection.immutable.HashSet
import actors.BuildStateActor.{BuildSucceeded, BuildFailed}

object LatestBuildMonitorSuite {
  val latestBuildMonitor = new LatestBuildMonitor(new IJobConfig {
    val name = "test"
  })

  val notBuildingJson =
    parse("""{
        "building" : true
       }""")

  val failingBuild =
    parse(
      """{
           "building" : false
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
           }
           "culprits" : [
              { "fullName" : "caoilte" },
              { "fullName" : "ben" },
              { "fullName" : "bruce" }
           ]
         }
      """)

  val passingBuild =
    parse(
      """{
           "actions" : [
              {
                "causes" : [
                  { "shortDescription" : "Started by an SCM change" }
                ]
              }
           ],
           "building" : false
           "number" : 3,
           "result" : "SUCCESS",
           "changeSet" : {
              "items" : [
              {
                "author" : { "fullName" : "caoilte" }
              }
              ]
           }
         }
      """)



}

class LatestBuildMonitorSuite extends WordSpec with MustMatchers {
  import LatestBuildMonitorSuite._

  // if all aok culprit and changeset include person who did commit



  "A Latest Build Monitor" when {
    "asked to transform a still building query response" must {
      "return nothing" in {
        assert(latestBuildMonitor.transformQueryResponse(notBuildingJson) === None)
      }
    }
    "asked to transform a failed build with two committers and a history of three committers" must {
      val buildState = latestBuildMonitor.transformQueryResponse(failingBuild).asInstanceOf[Option[BuildFailed]].get
      "correctly return the build number" in {
        assert(buildState.buildNumber === 5)
      }
      "return the most recent committers as a hashset" in {
        assert(buildState.committersThisBuild === new HashSet() + "caoilte" + "ben")
      }
      "return the committers since the previous good build as a hashset" in {
        assert(buildState.committersSincePreviousGoodBuild === new HashSet() + "caoilte" + "ben" + "bruce")
      }
    }
    "asked to transform a passing build with one committer" must {
      val buildState = latestBuildMonitor.transformQueryResponse(passingBuild).asInstanceOf[Option[BuildSucceeded]].get
      "correctly return the build number" in {
        assert(buildState.buildNumber === 3)
      }
      "return the most recent committers as a hashset" in {
        assert(buildState.committersThisBuild === new HashSet() + "caoilte")
      }
    }
  }

}
