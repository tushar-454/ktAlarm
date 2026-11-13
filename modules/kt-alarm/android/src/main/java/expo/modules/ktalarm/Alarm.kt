package expo.modules.ktalarm

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A single alarm.
 *
 * @param days repeat days, 0 = Sunday … 6 = Saturday. Empty = rings once.
 * @param snoozeUntil epoch millis of a pending snooze, or null.
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
) {
  val isRepeating: Boolean get() = days.isNotEmpty()

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
  )

  companion object {
    fun fromJson(o: JSONObject): Alarm {
      val daysJson = o.optJSONArray("days") ?: JSONArray()
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
      )
    }
  }
}
