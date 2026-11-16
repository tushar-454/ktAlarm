package expo.modules.ktalarm

import kotlin.math.exp

/** Orientation-independent, approximate steps from accelerometer magnitude (m/s²).
 * Requires three peaks at a walking cadence before crediting a bout; rejects isolated bumps,
 * vibration, very fast shaking and prolonged gaps. All timestamps are monotonic milliseconds.
 */
internal class WalkCounter {
  var steps = 0
    private set
  private var gravity = 9.81f
  private var lastSample = -1L
  private var lastPeak = -1L
  private var above = false
  private var bout = 0

  fun resetMotion() {
    lastSample = -1L
    lastPeak = -1L
    above = false
    bout = 0
  }

  /** Returns the number of newly credited steps. Does not allocate per sample. */
  fun update(magnitude: Float, now: Long): Int {
    if (!magnitude.isFinite()) return 0
    if (lastSample < 0 || now - lastSample > 1_500) {
      resetMotion()
      gravity = magnitude
      lastSample = now
      return 0
    }
    val dt = (now - lastSample).coerceIn(1, 200)
    lastSample = now
    val alpha = exp(-dt / 700f)
    gravity = alpha * gravity + (1 - alpha) * magnitude
    val acceleration = magnitude - gravity
    if (magnitude > 25f) {
      bout = 0
      lastPeak = -1L
      above = true
      return 0
    }
    if (acceleration < 0.3f) above = false
    if (above || acceleration < 1.2f) return 0
    above = true
    val gap = now - lastPeak
    if (lastPeak >= 0 && gap < 300) {
      bout = 0
      lastPeak = now
      return 0
    }
    bout = if (lastPeak < 0 || gap > 1_400) 1 else bout + 1
    lastPeak = now
    val added = when {
      bout < 3 -> 0
      bout == 3 -> 3
      else -> 1
    }
    steps += added
    return added
  }
}
