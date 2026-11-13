package expo.modules.ktalarm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A mission alarm that was ringing and has not been completed. Survives reboots so the alarm
 * can ring again as soon as the phone is back on.
 *
 * @param originalVolume the user's alarm-stream volume before ringing raised it, restored afterwards.
 */
data class PendingRing(val alarmId: String, val startedAt: Long, val originalVolume: Int)

/**
 * Persists alarms natively so receivers can re-arm them without the JS runtime.
 *
 * Uses device-protected storage, so alarms can be read (and ring) right after a reboot,
 * even before the user unlocks the phone for the first time (Direct Boot).
 */
object AlarmStore {
  private const val PREFS = "kt_alarm_store"
  private const val KEY_ALARMS = "alarms"
  private const val KEY_PENDING_RING = "pending_ring"

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext
      .createDeviceProtectedStorageContext()
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @Synchronized
  fun all(context: Context): List<Alarm> {
    val raw = prefs(context).getString(KEY_ALARMS, null) ?: return emptyList()
    return try {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
      emptyList()
    }
  }

  @Synchronized
  fun get(context: Context, id: String): Alarm? = all(context).firstOrNull { it.id == id }

  @Synchronized
  fun upsert(context: Context, alarm: Alarm) {
    val list = all(context).toMutableList()
    val index = list.indexOfFirst { it.id == alarm.id }
    if (index >= 0) list[index] = alarm else list.add(alarm)
    write(context, list)
  }

  @Synchronized
  fun remove(context: Context, id: String) {
    write(context, all(context).filterNot { it.id == id })
  }

  @Synchronized
  fun getPendingRing(context: Context): PendingRing? {
    val raw = prefs(context).getString(KEY_PENDING_RING, null) ?: return null
    return try {
      val o = JSONObject(raw)
      PendingRing(o.getString("alarmId"), o.getLong("startedAt"), o.optInt("originalVolume", -1))
    } catch (e: Exception) {
      null
    }
  }

  @Synchronized
  fun setPendingRing(context: Context, ring: PendingRing) {
    val o = JSONObject()
      .put("alarmId", ring.alarmId)
      .put("startedAt", ring.startedAt)
      .put("originalVolume", ring.originalVolume)
    prefs(context).edit().putString(KEY_PENDING_RING, o.toString()).commit()
  }

  @Synchronized
  fun clearPendingRing(context: Context) {
    prefs(context).edit().remove(KEY_PENDING_RING).commit()
  }

  private fun write(context: Context, alarms: List<Alarm>) {
    val arr = JSONArray()
    alarms.forEach { arr.put(it.toJson()) }
    // commit() (sync) on purpose: receivers may be killed right after returning.
    prefs(context).edit().putString(KEY_ALARMS, arr.toString()).commit()
  }
}
