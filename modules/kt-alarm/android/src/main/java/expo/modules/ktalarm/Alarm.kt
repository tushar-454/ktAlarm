package expo.modules.ktalarm

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A single alarm.
 *
 * @param days repeat days, 0 = Sunday … 6 = Saturday. Empty = rings once.
 * @param snoozeUntil epoch millis of a pending snooze, or null.
 * @param missions tasks required to dismiss the alarm. v1 uses at most one; a list keeps chains open.
 * @param maxSnoozes snoozes allowed per occurrence, or [UNLIMITED_SNOOZES].
 * @param snoozeCount snoozes already used for the current occurrence.
 */
data class Alarm(
  val id: String,
  val hour: Int,
  val minute: Int,
  val label: String,
  val days: List<Int>,
  val enabled: Boolean,
  val vibrate: Boolean,
  val snoozeMinutes: Int,
  val snoozeUntil: Long? = null,
  val missions: List<Mission> = emptyList(),
  val maxSnoozes: Int = UNLIMITED_SNOOZES,
  val snoozeCount: Int = 0,
) {
  val isRepeating: Boolean get() = days.isNotEmpty()
  val hasMission: Boolean get() = missions.isNotEmpty()
  val mission: Mission? get() = missions.firstOrNull()
  val canSnooze: Boolean get() = maxSnoozes == UNLIMITED_SNOOZES || snoozeCount < maxSnoozes

  /** Snoozes left, or null when unlimited. */
  val snoozesLeft: Int? get() = if (maxSnoozes == UNLIMITED_SNOOZES) null else (maxSnoozes - snoozeCount).coerceAtLeast(0)

  /**
   * A mission alarm that is ringing or snoozed cannot be switched off, edited or deleted —
   * otherwise "snooze, then delete" would skip the mission.
   */
  fun isLocked(now: Long = System.currentTimeMillis()): Boolean =
    hasMission && (AlarmService.activeAlarmId == id || (snoozeUntil ?: 0L) > now)

  /** Next epoch-millis this alarm should ring, strictly after [now]. */
  fun nextTrigger(now: Long = System.currentTimeMillis()): Long {
    snoozeUntil?.let { if (it > now) return it }

    val base = Calendar.getInstance().apply {
      timeInMillis = now
      set(Calendar.HOUR_OF_DAY, hour)
      set(Calendar.MINUTE, minute)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }

    if (!isRepeating) {
      if (base.timeInMillis <= now) base.add(Calendar.DAY_OF_YEAR, 1)
      return base.timeInMillis
    }

    for (offset in 0..7) {
      val candidate = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
      val weekday = candidate.get(Calendar.DAY_OF_WEEK) - 1 // Calendar.SUNDAY == 1
      if (weekday in days && candidate.timeInMillis > now) return candidate.timeInMillis
    }
    // Unreachable for a non-empty day set, but keep a sane fallback.
    return base.timeInMillis + 24L * 60 * 60 * 1000
  }

  fun toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("hour", hour)
    put("minute", minute)
    put("label", label)
    put("days", JSONArray(days))
    put("enabled", enabled)
    put("vibrate", vibrate)
    put("snoozeMinutes", snoozeMinutes)
    if (snoozeUntil != null) put("snoozeUntil", snoozeUntil)
    put("missions", JSONArray().apply { missions.forEach { put(it.toJson()) } })
    put("maxSnoozes", maxSnoozes)
    put("snoozeCount", snoozeCount)
  }

  /** Shape returned to JavaScript. */
  fun toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "hour" to hour,
    "minute" to minute,
    "label" to label,
    "days" to days,
    "enabled" to enabled,
    "vibrate" to vibrate,
    "snoozeMinutes" to snoozeMinutes,
    "snoozeUntil" to snoozeUntil?.toDouble(),
    "nextTriggerAt" to if (enabled) nextTrigger().toDouble() else null,
    "missions" to missions.map { it.toMap() },
    "maxSnoozes" to maxSnoozes,
    "snoozeCount" to snoozeCount,
    "locked" to isLocked(),
  )

  companion object {
    const val UNLIMITED_SNOOZES = -1

    fun fromJson(o: JSONObject): Alarm {
      val daysJson = o.optJSONArray("days") ?: JSONArray()
      val missionsJson = o.optJSONArray("missions") ?: JSONArray()
      return Alarm(
        id = o.getString("id"),
        hour = o.getInt("hour"),
        minute = o.getInt("minute"),
        label = o.optString("label", ""),
        days = (0 until daysJson.length()).map { daysJson.getInt(it) }.sorted(),
        enabled = o.optBoolean("enabled", true),
        vibrate = o.optBoolean("vibrate", true),
        snoozeMinutes = o.optInt("snoozeMinutes", 5),
        snoozeUntil = if (o.has("snoozeUntil")) o.getLong("snoozeUntil") else null,
        missions = (0 until missionsJson.length()).mapNotNull { Mission.fromJson(missionsJson.getJSONObject(it)) },
        maxSnoozes = o.optInt("maxSnoozes", UNLIMITED_SNOOZES),
        snoozeCount = o.optInt("snoozeCount", 0),
      )
    }
  }
}
