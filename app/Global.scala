import actors.{BuildMonitor}
import play.api._;
import scala.Some
;

object Global extends GlobalSettings {
  import actors.BuildMonitor.buildMonitor

  override def onStart(app: Application) {
    Logger.info("Application has started")

    buildMonitor = Some(new BuildMonitor);
  }

  override def onStop(app: Application) {

    buildMonitor.map(_.shutdown());
    Logger.info("Application shutdown...")
  }

}