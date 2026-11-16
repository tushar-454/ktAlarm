package expo.modules.ktalarm

import kotlin.math.exp
import kotlin.math.sqrt

/** Conservative fallback when Android's dedicated step detector is unavailable.
 * Separates gravity from motion, rejects lateral swings, and requires a full vertical stride
 * plus a stable walking cadence. Never credits buffered steps in a batch.
 */
internal class WalkCounter {
  var steps = 0
    private set
  private var gx = 0f
  private var gy = 0f
  private var gz = 9.81f
  private var vertical = 0f
  private var lastSample = -1L
  private var peakAt = -1L
  private var lastStride = -1L
  private var cadence = 0L
  private var consecutive = 0
  private var armed = true

  fun resetMotion() {
    lastSample = -1L
    peakAt = -1L
    lastStride = -1L
    cadence = 0
    consecutive = 0
    vertical = 0f
    armed = true
  }

  fun update(x: Float, y: Float, z: Float, now: Long): Int {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return 0
    if (lastSample < 0 || now - lastSample > 500) {
      resetMotion()
      gx = x; gy = y; gz = z
      lastSample = now
      return 0
    }
    if (now <= lastSample) return 0
    val dt = (now - lastSample).coerceAtMost(100)
    lastSample = now
    val gravityAlpha = exp(-dt / 1000f)
    gx = gravityAlpha * gx + (1 - gravityAlpha) * x
    gy = gravityAlpha * gy + (1 - gravityAlpha) * y
    gz = gravityAlpha * gz + (1 - gravityAlpha) * z
    val gravity = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1f)
    val lx = x - gx; val ly = y - gy; val lz = z - gz
    val projected = (lx * gx + ly * gy + lz * gz) / gravity
    val lateral = sqrt((lx * lx + ly * ly + lz * lz - projected * projected).coerceAtLeast(0f))
    val alpha = exp(-dt / 55f)
    vertical = alpha * vertical + (1 - alpha) * projected
    val magnitude = sqrt(x * x + y * y + z * z)
    if (magnitude > 22f || magnitude < 3f || lateral > maxOf(2.5f, kotlin.math.abs(projected) * 1.7f)) {
      peakAt = -1L; consecutive = 0; lastStride = -1L; armed = false
      return 0
    }
    if (vertical < 0.2f && peakAt < 0) armed = true
    if (peakAt < 0 && armed && vertical > 1.35f) {
      peakAt = now
      armed = false
    }
    if (peakAt < 0) return 0
    val phase = now - peakAt
    if (phase > 650) { peakAt = -1L; consecutive = 0; return 0 }
    if (vertical > -0.7f || phase < 100) return 0
    peakAt = -1L
    val gap = now - lastStride
    if (lastStride >= 0 && gap < 380) {
      consecutive = 0
      lastStride = now
      return 0
    }
    val steady = lastStride >= 0 && gap <= 1300 &&
      (cadence == 0L || kotlin.math.abs(gap - cadence) <= maxOf(180L, cadence / 3))
    consecutive = if (steady) consecutive + 1 else 1
    cadence = if (steady && cadence > 0) (cadence * 2 + gap) / 3 else if (steady) gap else 0
    lastStride = now
    // Two warm-up strides establish walking. Only the current stride is then credited.
    if (consecutive < 3) return 0
    steps++
    return 1
  }
}
