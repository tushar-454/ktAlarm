package expo.modules.ktalarm

import kotlin.math.hypot

/**
 * Counts squats from the body landmarks the camera sees. Pure logic, so it is unit tested.
 *
 * Each frame it reduces the legs to one number,
 *
 *   r = (knee.y − hip.y) / (ankle.y − knee.y)
 *
 * the vertical hip→knee distance relative to the vertical knee→ankle distance. Standing, thigh
 * and shin are about the same length, so r ≈ 1. Squatting, the hips drop towards knee height and
 * r goes to 0. This works whether the user faces the camera or stands side-on, doesn't care how
 * far away they are, and can't be faked by moving or tilting the phone (every joint moves
 * together). Lying in bed doesn't count either: the shins have to be upright.
 *
 * A rep counts only after the user was seen standing, then low enough, then standing again. The
 * standing value is calibrated per session, so camera height and body proportions don't matter.
 */
internal class SquatCounter {

  data class Point(val x: Float, val y: Float)

  /** One leg, in image pixels (y grows downward). */
  data class Leg(val hip: Point, val knee: Point, val ankle: Point)

  enum class State {
    /** No usable legs in view. */
    NOT_VISIBLE,
    /** In view; waiting to see the user standing up straight. */
    STAND_UP,
    /** Standing: go down. */
    READY,
    /** Low enough: stand back up to count it. */
    DOWN,
  }

  companion object {
    /** Down = r below this fraction of the standing value (thighs ~60° from vertical, or lower). */
    const val DOWN_FRACTION = 0.55f
    /** Standing again = r above this fraction of the standing value. */
    const val UP_FRACTION = 0.8f
    /** Plausible standing values of r, for calibration. */
    const val MIN_STANDING = 0.6f
    const val MAX_STANDING = 1.8f
    const val CALIBRATION_FRAMES = 5
    /** Frames in a row needed to switch between up and down, so one noisy frame can't. */
    const val CONFIRM_FRAMES = 2
    /** Out of view for this long: start over from standing (and recalibrate). */
    const val LOST_RESET_MS = 1_500L
    /** Shins smaller than this fraction of the frame height are too far away to trust. */
    const val MIN_SHIN_FRACTION = 0.04f
    private const val SMOOTHING = 0.5f
  }

  var state = State.NOT_VISIBLE
    private set
  var reps = 0
    private set
  /** 0 = standing tall … 1 = deep enough to count. Drives the depth meter. */
  var depth = 0f
    private set

  private var standing: Float? = null
  private var smoothed: Float? = null
  private var streak = 0
  private var lostAt = -1L
  private val calibration = ArrayList<Float>()

  /**
   * @param legs legs whose hip, knee and ankle are all in view (empty when none).
   * @param frameHeight height of the frame the points are in, in the same units.
   * @return true when this frame completed a squat.
   */
  fun update(legs: List<Leg>, frameHeight: Float, nowMs: Long): Boolean {
    val ratios = legs.mapNotNull { ratio(it, frameHeight) }
    if (ratios.isEmpty()) {
      lost(nowMs)
      return false
    }
    lostAt = -1L
    val r = ratios.average().toFloat()
    val s = smoothed?.let { it + SMOOTHING * (r - it) } ?: r
    smoothed = s
    if (state == State.NOT_VISIBLE) {
      state = State.STAND_UP
      streak = 0
    }

    val base = standing
    if (base == null) {
      calibrate(s)
      depth = 0f
      return false
    }
    depth = ((base - s) / (base * (1 - DOWN_FRACTION))).coerceIn(0f, 1f)
    val isUp = s >= base * UP_FRACTION
    val isDown = s <= base * DOWN_FRACTION
    if (isUp) adaptStanding(s)

    when (state) {
      State.STAND_UP -> if (confirm(isUp)) state = State.READY
      State.READY -> if (confirm(isDown)) state = State.DOWN
      State.DOWN -> if (confirm(isUp)) {
        state = State.READY
        reps++
        return true
      }
      State.NOT_VISIBLE -> Unit
    }
    return false
  }

  /** r for one leg, or null when the leg can't be trusted (lying down, too small). */
  private fun ratio(leg: Leg, frameHeight: Float): Float? {
    val shinDy = leg.ankle.y - leg.knee.y
    val shinLength = hypot(leg.ankle.x - leg.knee.x, shinDy)
    if (shinDy < MIN_SHIN_FRACTION * frameHeight || shinDy < 0.5f * shinLength) return null
    return (leg.knee.y - leg.hip.y) / shinDy
  }

  private fun confirm(condition: Boolean): Boolean {
    if (!condition) {
      streak = 0
      return false
    }
    if (++streak < CONFIRM_FRAMES) return false
    streak = 0
    return true
  }

  private fun calibrate(s: Float) {
    if (s < MIN_STANDING || s > MAX_STANDING) {
      calibration.clear()
      return
    }
    calibration.add(s)
    if (calibration.size < CALIBRATION_FRAMES) return
    standing = calibration.sorted()[calibration.size / 2]
    calibration.clear()
    state = State.READY
    streak = 0
  }

  /** Follows the real standing value: quickly up (calibrated while not fully upright), slowly down. */
  private fun adaptStanding(s: Float) {
    val base = standing ?: return
    val rate = if (s > base) 0.2f else 0.02f
    standing = (base + rate * (s - base)).coerceIn(MIN_STANDING, MAX_STANDING)
  }

  private fun lost(nowMs: Long) {
    if (lostAt < 0) lostAt = nowMs
    if (state == State.NOT_VISIBLE || nowMs - lostAt < LOST_RESET_MS) return
    state = State.NOT_VISIBLE
    standing = null
    smoothed = null
    streak = 0
    depth = 0f
    calibration.clear()
  }
}
