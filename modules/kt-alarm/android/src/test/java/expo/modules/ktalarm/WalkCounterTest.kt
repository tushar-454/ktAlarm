package expo.modules.ktalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WalkCounterTest {
  private val counter = WalkCounter()
  private var now = 0L

  private fun samples(duration: Long, magnitude: (Long) -> Float) {
    val end = now + duration
    while (now < end) {
      counter.update(magnitude(now), now)
      now += 20
    }
  }

  private fun walk(duration: Long, period: Long = 600) = samples(duration) {
    9.81f + 2.5f * sin(2 * PI * it / period).toFloat()
  }

  @Test fun stationaryPhoneAndSmallVibrationsDoNotCount() {
    samples(10_000) { 9.81f + 0.2f * sin(it.toDouble()).toFloat() }
    assertEquals(0, counter.steps)
  }

  @Test fun steadyWalkingCountsAtDifferentCadences() {
    for (period in listOf(400L, 600L, 1_000L)) {
      val c = WalkCounter()
      for (t in 0L until 12_000L step 20) {
        c.update(9.81f + 2.5f * sin(2 * PI * t / period).toFloat(), t)
      }
      val expected = (12_000 / period).toInt()
      assertTrue("period=$period, steps=${c.steps}", c.steps in expected - 1..expected)
    }
  }

  @Test fun isolatedBumpsDoNotCount() {
    repeat(5) {
      samples(500) { 9.81f }
      counter.update(13f, now)
      now += 20
      samples(2_000) { 9.81f }
    }
    assertEquals(0, counter.steps)
  }

  @Test fun strongShakingDoesNotCount() {
    samples(10_000) { if (it % 200 < 40) 30f else 9.81f }
    assertEquals(0, counter.steps)
  }

  @Test fun rapidModerateShakingDoesNotAliasIntoWalking() {
    samples(10_000) { 9.81f + 3f * sin(2 * PI * it / 160).toFloat() }
    assertEquals(0, counter.steps)
  }

  @Test fun pausePreservesCompletedStepsButRequiresNewWalkingBout() {
    walk(6_000)
    val before = counter.steps
    assertTrue(before >= 9)
    counter.resetMotion()
    samples(2_000) { 9.81f }
    assertEquals(before, counter.steps)
    walk(600)
    assertEquals(before, counter.steps)
    walk(3_000)
    assertTrue(counter.steps > before)
  }

  @Test fun invalidSensorSamplesAreIgnored() {
    counter.update(Float.NaN, now)
    counter.update(Float.POSITIVE_INFINITY, now)
    assertEquals(0, counter.steps)
  }
}
