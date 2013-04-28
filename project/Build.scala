import sbt._
import Keys._

object ApplicationBuild extends Build {

    val appName         = "build-monitor"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
      "io.spray" %  "spray-json_2.10" % "1.2.3",
      "com.typesafe.akka" % "akka-testkit_2.10" % "2.1.0",

      "io.spray" % "spray-client" % "1.1-M4.2",
      "io.spray" % "spray-io" % "1.1-M4.2",
      "org.scalaj" % "scalaj-time_2.10.0-M7" % "0.6"

//      "org.parboiled" % "parboiled-scala" % "1.0.2",
//      "org.parboiled" % "parboiled-core" % "1.0.2",
//      "org.jvnet.mimepull" % "mimepull" % "1.8"


//      "cc.spray" %  "spray-client" % "1.0-M3-SNAPSHOT"

  )

    val main = play.Project(appName, appVersion, appDependencies).settings(
      resolvers += "spray repo" at "http://repo.spray.io/"

    )



}
