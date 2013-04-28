import actors._
import akka.actor.{ActorRef, Actor, ActorSystem}

import akka.util.Timeout
import scala.concurrent.Await
import akka.testkit.{ImplicitSender, TestKit, TestActorRef}
import org.specs2.mutable._
import org.specs2.matcher.ThrownMessages
import org.specs2.time.NoTimeConversions

import akka.pattern.ask

import PrioritisedMessageFunnel._;

object PrioritisedMessageFunnelSuite {
  class MessageForwarder extends Actor {
    override def receive = {
      case (forwardAddress: ActorRef, message: PrioritisedMessage) => forwardAddress ! message
    }
  }

  case class TestFunnelMessage(payload: String) extends FunnelMessage

  val A_COMMAND = TestFunnelMessage("A_MESSAGE");
  val ANOTHER_COMMAND = TestFunnelMessage("ANOTHER_MESSAGE");
  val A_THIRD_COMMAND = TestFunnelMessage("A_THIRD_COMMAND");
}

/* A tiny class that can be used as a Specs2 'context'. */
abstract class AkkaTestkitSpecs2Support extends TestKit(ActorSystem()) with After with ImplicitSender {
  // make sure we shut down the actor system after all tests have run
  def after = system.shutdown()
}

class PrioritisedMessageFunnelSuite extends Specification with ThrownMessages with NoTimeConversions {
  import scala.concurrent.duration._

  import PrioritisedMessageFunnelSuite._;


  "A PrioritisedMessageFunnel sent one low priority message" should {
    "reply with that message when queried" in new AkkaTestkitSpecs2Support {

        implicit val timeout = Timeout(5 seconds)


        val d: Deadline = 5.seconds.fromNow

        val actorRef = TestActorRef[PrioritisedMessageFunnel];
        actorRef.receive(LowPriorityMessage(A_COMMAND))
        val result = Await.result((actorRef ? ReplyWithNextKarotzCommand(None)), 5 seconds).asInstanceOf[FunnelMessage]
        result must be(A_COMMAND)

    }
  }

  "A PrioritisedMessageFunnel sent two low priority messages from different sources" should {
      "reply with both messages in the order that they were sent" in new AkkaTestkitSpecs2Support {

        val forwarder1 = TestActorRef[MessageForwarder];
        val forwarder2 = TestActorRef[MessageForwarder];
        val mediator = TestActorRef[PrioritisedMessageFunnel];

        forwarder1 ! (mediator, LowPriorityMessage(A_COMMAND));
        forwarder2 ! (mediator, LowPriorityMessage(ANOTHER_COMMAND));
        mediator ! ReplyWithNextKarotzCommand(None);
        expectMsg(A_COMMAND)
        mediator ! ReplyWithNextKarotzCommand(None);
        expectMsg(ANOTHER_COMMAND)

    }
  }

  "A PrioritisedMessageFunnel sent three low priority messages from two different sources" should {
    "reply with two messages in the order that the two sources first sent them" in new AkkaTestkitSpecs2Support {

      val forwarder1 = TestActorRef[MessageForwarder];
      val forwarder2 = TestActorRef[MessageForwarder];
      val mediator = TestActorRef[PrioritisedMessageFunnel];

      forwarder1 ! (mediator, LowPriorityMessage(A_COMMAND));
      forwarder2 ! (mediator, LowPriorityMessage(ANOTHER_COMMAND));
      forwarder1 ! (mediator, LowPriorityMessage(A_THIRD_COMMAND));
      mediator ! ReplyWithNextKarotzCommand(None);
      expectMsg(A_THIRD_COMMAND)
      mediator ! ReplyWithNextKarotzCommand(None);
      expectMsg(ANOTHER_COMMAND)

    }
  }


  "A PrioritisedMessageFunnel sent a low priority message followed by a high priority message" should {
    "reply with the high priority message first" in new AkkaTestkitSpecs2Support {

      val forwarder1 = TestActorRef[MessageForwarder];
      val forwarder2 = TestActorRef[MessageForwarder];
      val mediator = TestActorRef[PrioritisedMessageFunnel];

      forwarder1 ! (mediator, LowPriorityMessage(A_COMMAND));
      forwarder2 ! (mediator, HighPriorityMessage(ANOTHER_COMMAND));
      mediator ! ReplyWithNextKarotzCommand(None);
      expectMsg(ANOTHER_COMMAND)
      mediator ! ReplyWithNextKarotzCommand(None);
      expectMsg(A_COMMAND)

    }
  }
}