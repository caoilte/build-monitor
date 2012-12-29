package actors.jenkins

import java.net.URI


object Query {

  def apply(jobName: String, apiName: String = ""): String = {
      "/job/" + jobName + apiName + "/api/json"
  }
}
