package expo.modules.ktalarm

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepDeadlineTest {
  @Test fun stopsAtElapsedDeadlineEvenWhenTheWallClockChanges() {
    val timer = SleepDeadline(1000, 1_700_000_000_000, 30)
    assertEquals(30 * 60_000L, timer.remaining(1000))
    assertEquals(15 * 60_000L, timer.remaining(1000 + 15 * 60_000L))
    assertEquals(0, timer.remaining(timer.endElapsed))
    assertEquals(0, timer.remaining(timer.endElapsed + 60_000))
  }
  @Test fun longTimersDoNotOverflowAndShortTracksCanRepeatForTheWholeDuration() {
    val timer = SleepDeadline(4000, 1_700_000_000_000, 720)
    assertEquals(43_200_000L, timer.durationMs)
    assertEquals(43_188_000L, timer.remaining(4000 + 12_000))
    assertEquals(1_700_043_200_000L, timer.endWall)
  }
  @Test(expected = IllegalArgumentException::class) fun rejectsEmptyTimer() {
    SleepDeadline(0, 0, 0)
  }
  @Test(expected = IllegalArgumentException::class) fun rejectsExcessiveTimer() {
    SleepDeadline(0, 0, 721)
  }
}
