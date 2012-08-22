package actors.messaging

import akka.actor.Actor
import config.KarotzConfig
import collection.mutable.HashMap
import actors.messaging.NameGeneratingActor.{NamesStringReply, NamesStringRequest}
import annotation.tailrec


object NameGeneratingActor {
  case class NamesStringRequest(names: Set[String])
  case class NamesStringReply(namesString: String)
}

class NameGeneratingActor(karotzConfig: KarotzConfig) extends Actor {

  val nameMap = karotzConfig.people.foldLeft(new HashMap[String, String]) { (map, person) =>
    map.+=((person.userName, person.karotzName));
    map
  }

  protected def receive = {
    case NamesStringRequest(namesSet) => {
      val names = namesSet.flatMap(nameMap.get(_)).toList
      val namesString = generateNameString(new StringBuilder(), names);
      sender ! NamesStringReply(namesString);
    }
  }

  @tailrec
  private def generateNameString(names: StringBuilder, namesList: List[String]): String = namesList match {
    case Nil => names.toString();
    case one :: Nil => names.append(one).toString();
    case one :: two :: Nil => names.append(one).append(" and ").append(two).toString()
    case one :: list => generateNameString(names.append(one).append(", "), list);
  }
}
