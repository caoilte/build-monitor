import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "build-monitor"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
      "net.databinder.dispatch" %% "core" % "0.9.0",
      "net.liftweb" %% "lift-json" % "2.4",
      "cc.spray" %  "spray-client" % "1.0-M2.2",
      "org.scalatest" %% "scalatest" % "1.8" % "test",
      "com.typesafe.akka" % "akka-testkit" % "2.0.2"

  )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      testOptions in Test := Nil,
      resolvers += "spray repo" at "http://repo.spray.cc/"

    )



}
