package controllers

import play.api._
import data.Form
import play.api.mvc._
import play.api.data.Forms._
import akka.pattern.ask
import play.api.libs.concurrent._
import actors.PrioritisedMessageFunnel.LowPriorityMessage
import actors.karotz.Karotz.{SpeechAction, KarotzMessage}

object Application extends Controller {

  def buildMonitor = actors.BuildMonitor.buildMonitor.get.system
  def funnel = buildMonitor.actorFor("/user/buildMonitoringSupervisor/karotzMessageFunnel")

  val taskForm = Form(
    "text" -> nonEmptyText
  )
  
  def index = Action {
    Redirect(routes.Application.offerSpeak)
  }

  def offerSpeak = Action {
    Ok(views.html.speech(taskForm))
  }
  def doSpeak = Action { implicit request =>
    taskForm.bindFromRequest.fold(
      errors => BadRequest(views.html.speech(errors)),
      text => {
        funnel ! LowPriorityMessage(KarotzMessage(SpeechAction(text)))

        Redirect(routes.Application.offerSpeak)
      }
    )
  }
  
}