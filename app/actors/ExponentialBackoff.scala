package actors

import scala.concurrent.Await
import scala.concurrent.duration._
import util.Random

case class ExponentialBackOff(slotTime: FiniteDuration, ceiling: Int = 10, stayAtCeiling: Boolean = false,
                              slot: Int = 1, rand: Random = new Random(), waitTime: FiniteDuration = Duration.Zero,
                              retries: Int = 0, resets: Int = 0, totalRetries: Long = 0) {
  def isStarted = retries > 0

  def reset(): ExponentialBackOff = {
    if (slot > 1) {
      copy(slot = 1, waitTime = Duration.Zero, resets = resets + 1, retries = 0)
    } else this
  }

  def nextBackOff: ExponentialBackOff = {
    def time: FiniteDuration = slotTime * times
    def times = {
      val exp = slot //rand.nextInt(slot + 1)
      math.round(math.pow(2, exp) - 1)
    }
    if (slot >= ceiling && !stayAtCeiling) reset()
    else {
      val (newSlot, newWait: FiniteDuration) = if (slot >= ceiling) {
        (ceiling, time)
      } else {
        (slot + 1, time)
      }
      copy(slot = newSlot,
        waitTime = newWait,
        retries = retries + 1,
        totalRetries = totalRetries + 1)
    }
  }
}