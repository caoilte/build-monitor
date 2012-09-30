package config

import com.typesafe.config.{ConfigFactory, Config}

import scala.collection.JavaConversions._
import collection.breakOut



class JenkinsConfig(c: Config) {
  val url = c getString "url"
  val port = c getInt "port"
  val userName = c getString "userName"
  val password = c getString "password"
}

class PeopleConfig(c: Config) {
  val userName = c getString "userName"
  val karotzName = c getString "karotzName"
}

class KarotzConfig(c: Config) {
  val people = {
    val peopleList = c getConfigList("people")
    peopleList.map(new PeopleConfig(_))(breakOut) : List[PeopleConfig]
  }
  val apiKey = c getString "apiKey"
  val secretKey = c getString "secretKey"
  val installId = c getString "installId"
}

class JobConfig(c: Config) {
  val name = c getString "name"
}

class GlobalConfig(config: Config = ConfigFactory.load()) {
  private[this] val c: Config = {
    val c = config.withFallback(ConfigFactory.defaultReference)
    c.checkValid(ConfigFactory.defaultReference, "buildMonitor")
    c.getConfig("buildMonitor")
  }

  val jenkinsConfig = new JenkinsConfig(c getConfig "jenkinsConfig")
  val karotzConfig = new KarotzConfig(c getConfig "karotzConfig")
  val jobs = {
    val jobsList = c getConfigList "jobs"
    jobsList.map(new JobConfig(_))(breakOut) : List[JobConfig]
  }
}
