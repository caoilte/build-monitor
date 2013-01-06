package actors.messaging

import akka.actor.{ActorLogging, Actor}
import config.KarotzConfig
import collection.mutable.HashMap
import actors.messaging.NameGeneratingActor.{NamesStringReply, NamesStringRequest}
import annotation.tailrec


object NameGeneratingActor {
  case class NamesStringRequest(names: Set[String])
  case class NamesStringReply(namesString: String)
}

class NameGeneratingActor(karotzConfig: KarotzConfig) extends Actor with ActorLogging {

  val nameMap = karotzConfig.people.foldLeft(new HashMap[String, String]) { (map, person) =>
    map.+=((person.userName, person.karotzName));
    map
  }

  override def receive = {
    case NamesStringRequest(namesSet) => {
      val names = namesSet.flatMap(nameMap.get(_)).toList

      val namesString = names match {
        case list :: tail => generateNameString(new StringBuilder(), names);
        case Nil => {
          "hmmmmmm. some mysterious person who i do not recognise."
        };
      }
      if (names.size < namesSet.size) {
        val noNameMappings = namesSet.filter(!nameMap.contains(_))
        log.warning("No name mapping was found for the following user names {}", noNameMappings)
      }
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
