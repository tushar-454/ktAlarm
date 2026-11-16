package expo.modules.ktalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

class WalkCounterTest {
  private var now = 0L
  private val counter = WalkCounter()
  private fun samples(duration: Long, sample: (Long) -> FloatArray) {
    val start = now
    while (now < start + duration) {
      val v = sample(now - start)
      val added = counter.update(v[0], v[1], v[2], now)
      assertTrue("Never add a burst of steps", added == 0 || added == 1)
      now += 20
    }
  }
  private fun still(duration: Long) = samples(duration) { floatArrayOf(0f, 0f, 9.81f) }
  private fun walk(duration: Long, period: Long = 600) = samples(duration) {
    floatArrayOf(0.4f * sin(2 * PI * it / period).toFloat(), 0f,
      9.81f + 2.7f * sin(2 * PI * it / period).toFloat())
  }

  @Test fun restingAndAlarmVibrationDoNotCount() {
    samples(10_000) { floatArrayOf(0.1f * sin(it.toDouble()).toFloat(), 0f,
      9.81f + 0.25f * sin(it.toDouble()).toFloat()) }
    assertEquals(0, counter.steps)
  }

  @Test fun movingPhoneBackAndForthDoesNotCount() {
    for (period in listOf(300L, 600L, 1200L, 2400L)) {
      samples(10_000) { floatArrayOf(6f * sin(2 * PI * it / period).toFloat(), 0f, 9.81f) }
    }
    assertEquals(0, counter.steps)
  }

  @Test fun rotatingPhoneInPlaceDoesNotCount() {
    samples(12_000) {
      val angle = PI / 3 * sin(2 * PI * it / 2000)
      floatArrayOf((9.81 * sin(angle)).toFloat(), 0f, (9.81 * cos(angle)).toFloat())
    }
    assertEquals(0, counter.steps)
  }

  @Test fun walkingAtNaturalCadencesCountsOneStrideAtATimeAfterWarmup() {
    for (period in listOf(500L, 650L, 1000L)) {
      val c = WalkCounter()
      val expected = (15_000 / period).toInt()
      for (t in 0L until 15_000 step 20) {
        val add = c.update(0.3f, 0f, 9.81f + 2.7f * sin(2 * PI * t / period).toFloat(), t)
        assertTrue(add in 0..1)
      }
      assertTrue("period=$period steps=${c.steps} expected~${expected - 2}", c.steps in expected - 4..expected - 2)
    }
  }

  @Test fun stepsAreIndependentOfPhoneOrientation() {
    val c = WalkCounter()
    for (t in 0L until 12_000 step 20) {
      c.update(9.81f + 2.7f * sin(2 * PI * t / 600).toFloat(), 0f, 0f, t)
    }
    assertTrue(c.steps in 16..18)
  }

  @Test fun isolatedCompleteBumpsDoNotBecomeSteps() {
    repeat(8) { walk(600); still(2000) }
    assertEquals(0, counter.steps)
  }

  @Test fun fastShakingDoesNotCount() {
    walk(10_000, 160)
    assertEquals(0, counter.steps)
    samples(10_000) { floatArrayOf(0f, 0f, if (it % 200 < 40) 30f else 9.81f) }
    assertEquals(0, counter.steps)
  }

  @Test fun gentleMovingWithoutWalkingDoesNotCount() {
    samples(10_000) { floatArrayOf(0.6f, 0.4f, 9.81f + 0.8f * sin(2 * PI * it / 600).toFloat()) }
    assertEquals(0, counter.steps)
  }

  @Test fun inconsistentVerticalBumpsDoNotFormWalkingCadence() {
    repeat(6) {
      for (gap in listOf(450L, 1200L)) {
        samples(300) { floatArrayOf(0f, 0f, 9.81f + 3f * sin(2 * PI * it / 300).toFloat()) }
        still(gap - 300)
      }
    }
    assertEquals(0, counter.steps)
  }

  @Test fun pausePreservesProgressWithoutCreditingBufferedSteps() {
    walk(6000)
    val before = counter.steps
    assertTrue(before >= 6)
    counter.resetMotion()
    still(2000)
    walk(600)
    assertEquals(before, counter.steps)
    walk(3000)
    assertTrue(counter.steps > before)
  }

  @Test fun invalidSamplesAreIgnored() {
    counter.update(Float.NaN, 0f, 9.81f, now)
    counter.update(0f, Float.POSITIVE_INFINITY, 9.81f, now)
    assertEquals(0, counter.steps)
  }
}
