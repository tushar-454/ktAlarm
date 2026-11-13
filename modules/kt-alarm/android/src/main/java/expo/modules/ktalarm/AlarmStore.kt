package expo.modules.ktalarm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Persists alarms natively so receivers can re-arm them without the JS runtime.
 *
 * Uses device-protected storage, so alarms can be read (and ring) right after a reboot,
 * even before the user unlocks the phone for the first time (Direct Boot).
 */
object AlarmStore {
  private const val PREFS = "kt_alarm_store"
  private const val KEY_ALARMS = "alarms"

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

  private fun write(context: Context, alarms: List<Alarm>) {
    val arr = JSONArray()
    alarms.forEach { arr.put(it.toJson()) }
    // commit() (sync) on purpose: receivers may be killed right after returning.
    prefs(context).edit().putString(KEY_ALARMS, arr.toString()).commit()
  }
}
