import actors._
import akka.actor.{ActorRef, Actor, ActorSystem}
import akka.dispatch.Await
import akka.testkit.{ImplicitSender, TestKit, TestActorRef}
import akka.util.{Deadline, Duration, Timeout}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.scalatest.matchers.MustMatchers
import akka.util.duration._
import akka.pattern.ask

import PrioritisedMessageFunnel._;

object PrioritisedMessageFunnelSuite {
  class MessageForwarder extends Actor {
    protected def receive = {
      case (forwardAddress: ActorRef, message: PrioritisedMessage) => forwardAddress ! message
    }
  }

  case class TestFunnelMessage(payload: String) extends FunnelMessage

  val A_COMMAND = TestFunnelMessage("A_MESSAGE");
  val ANOTHER_COMMAND = TestFunnelMessage("ANOTHER_MESSAGE");
  val A_THIRD_COMMAND = TestFunnelMessage("A_THIRD_COMMAND");
}

class PrioritisedMessageFunnelSuite(_system: ActorSystem) extends TestKit(_system)
with WordSpec with MustMatchers with ImplicitSender with BeforeAndAfterAll {

  import PrioritisedMessageFunnelSuite._;

  def this() = this(ActorSystem("MySpec"))

  implicit val timeout = Timeout(5 seconds)

  def fixture = new {
    val actorRef = TestActorRef[PrioritisedMessageFunnel];
  }

  override def afterAll {
    system.shutdown()
  }

  "A PrioritisedMessageFunnel" when {
    "sent one low priority message" must {
      "reply with that message when queried" in {


        val d: Deadline = 5.seconds.fromNow

        val actorRef = fixture.actorRef;
        actorRef.receive(LowPriorityMessage(A_COMMAND))
        val result = Await.result((actorRef ? ReplyWithNextKarotzCommand(None)), 5 seconds).asInstanceOf[FunnelMessage]
        result must be(A_COMMAND)

      }
    }

    "sent two low priority messages from different sources" must {
      "reply with both messages in the order that they were sent" in {

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

    "sent three low priority messages from two different sources" must {
      "reply with two messages in the order that the two sources first sent them" in {

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


    "sent a low priority message followed by a high priority message" must {
      "reply with the high priority message first" in {

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
}