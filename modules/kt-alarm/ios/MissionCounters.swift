import Foundation

/// Uses the same full-stride/cadence rules as Android's fallback. Input is m/s².
struct MissionWalkCounter {
  private var gravity = (0.0, 0.0, 9.81)
  private var vertical = 0.0
  private var lastSample: Double?
  private var peakAt: Double?
  private var lastStride: Double?
  private var cadence = 0.0
  private var consecutive = 0
  private var armed = true

  mutating func update(x: Double, y: Double, z: Double, now: Double) -> Bool {
    guard x.isFinite, y.isFinite, z.isFinite else { return false }
    guard let lastSample, now - lastSample <= 0.5 else {
      self = MissionWalkCounter()
      gravity = (x, y, z); self.lastSample = now
      return false
    }
    guard now > lastSample else { return false }
    let dt = min(0.1, now - lastSample)
    self.lastSample = now
    let alpha = exp(-dt)
    gravity = (alpha * gravity.0 + (1 - alpha) * x,
      alpha * gravity.1 + (1 - alpha) * y, alpha * gravity.2 + (1 - alpha) * z)
    let g = max(1, sqrt(gravity.0 * gravity.0 + gravity.1 * gravity.1 + gravity.2 * gravity.2))
    let lx = x - gravity.0, ly = y - gravity.1, lz = z - gravity.2
    let projected = (lx * gravity.0 + ly * gravity.1 + lz * gravity.2) / g
    let lateral = sqrt(max(0, lx * lx + ly * ly + lz * lz - projected * projected))
    let smoothing = exp(-dt / 0.055)
    vertical = smoothing * vertical + (1 - smoothing) * projected
    let magnitude = sqrt(x * x + y * y + z * z)
    if magnitude > 22 || magnitude < 3 || lateral > max(2.5, abs(projected) * 1.7) {
      peakAt = nil; consecutive = 0; lastStride = nil; armed = false
      return false
    }
    if vertical < 0.2 && peakAt == nil { armed = true }
    if peakAt == nil && armed && vertical > 1.35 { peakAt = now; armed = false }
    guard let peakAt else { return false }
    let phase = now - peakAt
    if phase > 0.65 { self.peakAt = nil; consecutive = 0; return false }
    guard vertical <= -0.7, phase >= 0.1 else { return false }
    self.peakAt = nil
    let gap = lastStride.map { now - $0 } ?? .infinity
    if gap < 0.38 { consecutive = 0; self.lastStride = now; return false }
    let steady = gap <= 1.3 && (cadence == 0 || abs(gap - cadence) <= max(0.18, cadence / 3))
    consecutive = steady ? consecutive + 1 : 1
    cadence = steady ? (cadence > 0 ? (cadence * 2 + gap) / 3 : gap) : 0
    self.lastStride = now
    return consecutive >= 3
  }
}

/// A rep requires calibrated standing → low hips → standing; lost tracking resets the pose.
struct MissionSquatCounter {
  enum Phase { case calibrating, standing, down }
  private(set) var phase = Phase.calibrating
  private var calibration: [Double] = []
  private var standing: Double?
  private var smoothed: Double?
  private var streak = 0
  private var lastVisible = 0.0

  mutating func update(ratios: [Double], now: Double) -> Bool {
    guard !ratios.isEmpty else {
      if now - lastVisible > 1.5 { self = MissionSquatCounter() }
      return false
    }
    lastVisible = now
    let r = ratios.reduce(0, +) / Double(ratios.count)
    let value = smoothed.map { $0 + 0.5 * (r - $0) } ?? r
    smoothed = value
    guard let base = standing else {
      guard (0.6...1.8).contains(value) else { calibration.removeAll(); return false }
      calibration.append(value)
      if calibration.count >= 5 {
        standing = calibration.sorted()[calibration.count / 2]
        phase = .standing; calibration.removeAll()
      }
      return false
    }
    let up = value >= base * 0.8
    if up { standing = min(1.8, max(0.6, base + (value > base ? 0.2 : 0.02) * (value - base))) }
    let confirmed = phase == .down ? up : value <= base * 0.55
    streak = confirmed ? streak + 1 : 0
    guard streak >= 2 else { return false }
    streak = 0
    if phase == .down { phase = .standing; return true }
    phase = .down
    return false
  }
}
