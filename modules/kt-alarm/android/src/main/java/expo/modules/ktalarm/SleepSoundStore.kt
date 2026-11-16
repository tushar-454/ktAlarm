package expo.modules.ktalarm

import android.content.Context

internal data class SleepSelection(val uri: String, val name: String, val minutes: Int, val alarmId: String? = null)
internal data class SleepPlayback(val selection: SleepSelection, val endsAt: Long?, val preparing: Boolean)

internal object SleepSoundStore {
  @Volatile var current: SleepPlayback? = null
  @Volatile var error: String? = null
  private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
    .getSharedPreferences("kt_sleep_sounds", Context.MODE_PRIVATE)

  fun selection(context: Context): SleepSelection {
    val p = prefs(context)
    return SleepSelection(p.getString("uri", "preset:pink") ?: "preset:pink",
      p.getString("name", "Pink noise") ?: "Pink noise", p.getInt("minutes", 30).coerceIn(1, 720))
  }

  fun save(context: Context, selection: SleepSelection) {
    prefs(context).edit().putString("uri", selection.uri).putString("name", selection.name)
      .putInt("minutes", selection.minutes).apply()
  }

  fun state(context: Context): Map<String, Any?> {
    val playback = current
    val selection = playback?.selection ?: selection(context)
    return mapOf("playing" to (playback != null && !playback.preparing),
      "preparing" to (playback?.preparing == true), "uri" to selection.uri, "name" to selection.name,
      "minutes" to selection.minutes, "alarmId" to playback?.selection?.alarmId,
      "endsAt" to playback?.endsAt?.toDouble(), "error" to error)
  }

  fun changed() = AlarmEvents.emit(AlarmEvents.Type.SLEEP_SOUND_CHANGED)
}

/** Uses elapsed time for duration, so changing the device clock cannot extend playback. */
internal data class SleepDeadline(val startElapsed: Long, val startWall: Long, val minutes: Int) {
  init { require(minutes in 1..720) }
  val durationMs: Long get() = minutes * 60_000L
  val endElapsed: Long get() = startElapsed + durationMs
  val endWall: Long get() = startWall + durationMs
  fun remaining(nowElapsed: Long): Long = (endElapsed - nowElapsed).coerceIn(0, durationMs)
}
