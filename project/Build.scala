import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "build-monitor"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
      "net.liftweb" %% "lift-json" % "2.4",
      "org.scalatest" %% "scalatest" % "1.8" % "test",
      "com.typesafe.akka" % "akka-testkit" % "2.0.2",
      "org.parboiled" % "parboiled-scala" % "1.0.2",
      "org.parboiled" % "parboiled-core" % "1.0.2",
      "org.jvnet.mimepull" % "mimepull" % "1.8"


//      "cc.spray" %  "spray-client" % "1.0-M3-SNAPSHOT"

  )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      testOptions in Test := Nil,
      resolvers += "spray repo" at "http://repo.spray.cc/"

    )



}
