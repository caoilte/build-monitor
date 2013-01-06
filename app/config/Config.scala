package config

import com.typesafe.config.{ConfigFactory, Config}

import scala.collection.JavaConversions._
import collection.breakOut

trait IJenkinsConfig {
  val url: String
  val port: Int
  val userName: String
  val password: String
}

class JenkinsConfig(c: Config) extends IJenkinsConfig {
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

  val ipAddress = c getString "ipAddress"
  val port = c getInt "port"
}

trait IJobConfig {
  val name: String
  def underScoredName: String
}

class JobConfig(c: Config) extends IJobConfig {
  val name = c getString "name"
  def underScoredName = name.replace(' ', '_');
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
