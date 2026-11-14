package expo.modules.ktalarm

import expo.modules.ktalarm.SquatCounter.Leg
import expo.modules.ktalarm.SquatCounter.Point
import expo.modules.ktalarm.SquatCounter.State
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SquatCounterTest {

  private val frameHeight = 1000f
  private var now = 0L
  private val counter = SquatCounter()

  /**
   * A leg seen side-on. [thighDeg] is the thigh's angle from vertical: 0 standing, ~90 at the
   * bottom of a full squat. Shins lean forward a bit as the user goes down, like real squats.
   */
  private fun leg(
    thighDeg: Double,
    length: Float = 220f,
    ankleX: Float = 500f,
    ankleY: Float = 900f,
    rotateDeg: Double = 0.0,
  ): Leg {
    val shin = Math.toRadians(thighDeg * 0.35)
    val thigh = Math.toRadians(thighDeg)
    val ankle = Point(ankleX, ankleY)
    val knee = Point(ankleX + (sin(shin) * length).toFloat(), ankleY - (cos(shin) * length).toFloat())
    val hip = Point(knee.x - (sin(thigh) * length).toFloat(), knee.y - (cos(thigh) * length).toFloat())
    return if (rotateDeg == 0.0) Leg(hip, knee, ankle) else Leg(rotate(hip, ankle, rotateDeg), rotate(knee, ankle, rotateDeg), ankle)
  }

  private fun rotate(p: Point, around: Point, deg: Double): Point {
    val a = Math.toRadians(deg)
    val dx = p.x - around.x
    val dy = p.y - around.y
    return Point(around.x + (dx * cos(a) - dy * sin(a)).toFloat(), around.y + (dx * sin(a) + dy * cos(a)).toFloat())
  }

  /** Feeds frames at ~20 fps, returns how many reps were completed. */
  private fun frames(count: Int, legs: (Int) -> List<Leg>): Int {
    var done = 0
    repeat(count) { i ->
      now += 50
      if (counter.update(legs(i), frameHeight, now)) done++
    }
    return done
  }

  private fun stand(frames: Int = 10) = frames(frames) { listOf(leg(0.0), leg(2.0)) }

  /** Smoothly down to [bottomDeg] and back up over ~1.5 s. */
  private fun squat(bottomDeg: Double = 90.0): Int {
    val steps = 15
    val down = frames(steps) { i -> listOf(leg(bottomDeg * (i + 1) / steps)) }
    val bottom = frames(4) { listOf(leg(bottomDeg)) }
    val up = frames(steps) { i -> listOf(leg(bottomDeg * (steps - i - 1) / steps)) }
    return down + bottom + up
  }

  @Test
  fun countsFullSquats() {
    stand()
    assertEquals(State.READY, counter.state)
    repeat(3) { assertEquals(1, squat()) }
    assertEquals(3, counter.reps)
  }

  @Test
  fun countsSquatsToAboutSixtyDegrees() {
    stand()
    assertEquals(1, squat(bottomDeg = 65.0))
  }

  @Test
  fun ignoresShallowKneeBends() {
    stand()
    repeat(5) { assertEquals(0, squat(bottomDeg = 30.0)) }
    assertEquals(0, counter.reps)
  }

  @Test
  fun jitterAtTheBottomCountsOnce() {
    stand()
    frames(15) { i -> listOf(leg(90.0 * (i + 1) / 15)) }
    // Noisy landmarks while holding the bottom.
    frames(30) { i -> listOf(leg(if (i % 2 == 0) 80.0 else 95.0)) }
    val reps = frames(15) { i -> listOf(leg(90.0 * (14 - i) / 15)) }
    assertEquals(1, reps)
  }

  @Test
  fun hasToBeSeenStandingFirst() {
    // Starts at the bottom: not a rep until a full stand → down → stand.
    assertEquals(0, frames(10) { listOf(leg(90.0)) })
    assertEquals(State.STAND_UP, counter.state)
    assertEquals(0, stand())
    assertEquals(1, squat())
  }

  @Test
  fun lyingDownNeverCounts() {
    // Whole body rotated 90° (lying in bed): shins aren't upright.
    repeat(3) {
      frames(15) { i -> listOf(leg(90.0 * (i + 1) / 15, rotateDeg = 90.0)) }
      frames(15) { i -> listOf(leg(90.0 * (14 - i) / 15, rotateDeg = 90.0)) }
    }
    assertEquals(0, counter.reps)
    assertEquals(State.NOT_VISIBLE, counter.state)
  }

  @Test
  fun movingTheCameraDoesNotCount() {
    stand()
    // Standing still while the phone moves closer/further and up/down.
    val reps = frames(60) { i ->
      val scale = 140f + (i % 20) * 8f
      listOf(leg(0.0, length = scale, ankleX = 300f + i * 3, ankleY = 700f + (i % 10) * 25))
    }
    assertEquals(0, reps)
  }

  @Test
  fun tiltedPhoneStillCounts() {
    frames(10) { listOf(leg(0.0, rotateDeg = 15.0)) }
    val steps = 15
    val reps = frames(steps) { i -> listOf(leg(90.0 * (i + 1) / steps, rotateDeg = 15.0)) } +
      frames(4) { listOf(leg(90.0, rotateDeg = 15.0)) } +
      frames(steps) { i -> listOf(leg(90.0 * (steps - i - 1) / steps, rotateDeg = 15.0)) }
    assertEquals(1, reps)
  }

  @Test
  fun calibratedWhileHalfCrouchedStillNeedsARealSquat() {
    frames(10) { listOf(leg(50.0)) } // calibrates on a half-crouch
    stand(20) // standing up straight is not a rep
    assertEquals(0, counter.reps)
    assertEquals(1, squat())
  }

  @Test
  fun leavingTheFrameStartsOver() {
    stand()
    frames(15) { i -> listOf(leg(90.0 * (i + 1) / 15)) }
    assertEquals(State.DOWN, counter.state)
    frames(40) { emptyList() } // 2 s out of view
    assertEquals(State.NOT_VISIBLE, counter.state)
    // Coming back standing doesn't complete the rep it never saw finish.
    assertEquals(0, stand())
    assertEquals(0, counter.reps)
  }

  @Test
  fun briefDropoutDoesNotLoseTheRep() {
    stand()
    frames(15) { i -> listOf(leg(90.0 * (i + 1) / 15)) }
    frames(5) { emptyList() } // 250 ms of missed detections
    assertEquals(1, frames(15) { i -> listOf(leg(90.0 * (14 - i) / 15)) })
  }

  @Test
  fun tinyFarAwayLegsAreIgnored() {
    frames(20) { listOf(leg(0.0, length = 20f)) }
    assertEquals(State.NOT_VISIBLE, counter.state)
  }

  @Test
  fun depthGoesFromZeroToOne() {
    stand()
    assertEquals(0f, counter.depth, 0.05f)
    frames(15) { i -> listOf(leg(90.0 * (i + 1) / 15)) }
    frames(5) { listOf(leg(90.0)) }
    assertEquals(1f, counter.depth, 0.001f)
  }
}
