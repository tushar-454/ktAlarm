package expo.modules.ktalarm

import android.content.Context
import android.content.Intent

/** All state transitions of an alarm live here, so UI, notification and JS share one path. */
object AlarmController {

  fun save(context: Context, alarm: Alarm) {
    AlarmStore.upsert(context, alarm)
    AlarmScheduler.schedule(context, alarm)
    AlarmEvents.emit(AlarmEvents.Type.ALARMS_CHANGED)
  }

  fun delete(context: Context, id: String) {
    AlarmScheduler.cancel(context, id)
    AlarmStore.remove(context, id)
    if (AlarmService.activeAlarmId == id) stopRinging(context)
    AlarmEvents.emit(AlarmEvents.Type.ALARMS_CHANGED)
  }

  fun setEnabled(context: Context, id: String, enabled: Boolean) {
    val alarm = AlarmStore.get(context, id) ?: return
    save(context, alarm.copy(enabled = enabled, snoozeUntil = null))
  }

  /**
   * Called the moment an alarm goes off. Advances the alarm right away, so a crash or
   * reboot while ringing can never make it ring again for the same occurrence.
   */
  fun onFired(context: Context, id: String): Alarm? {
    val alarm = AlarmStore.get(context, id) ?: return null
    val advanced = if (alarm.isRepeating) {
      alarm.copy(snoozeUntil = null)
    } else {
      alarm.copy(enabled = false, snoozeUntil = null)
    }
    AlarmStore.upsert(context, advanced)
    if (advanced.isRepeating && advanced.enabled) {
      // Compute from one minute ahead so we never re-arm the occurrence that is ringing now.
      AlarmScheduler.schedule(context, advanced, advanced.nextTrigger(System.currentTimeMillis() + 60_000))
    }
    AlarmEvents.emit(AlarmEvents.Type.ALARMS_CHANGED)
    return alarm
  }

  fun snooze(context: Context, id: String) {
    val alarm = AlarmStore.get(context, id)
    if (alarm != null) {
      val until = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
      save(context, alarm.copy(enabled = true, snoozeUntil = until))
    }
    stopRinging(context)
  }

  fun dismiss(context: Context, id: String) {
    val alarm = AlarmStore.get(context, id)
    if (alarm != null && alarm.snoozeUntil != null) {
      // Dismissing a snoozed alarm cancels the snooze.
      save(context, alarm.copy(snoozeUntil = null, enabled = alarm.isRepeating && alarm.enabled))
    }
    stopRinging(context)
  }

  fun stopRinging(context: Context) {
    context.stopService(Intent(context, AlarmService::class.java))
  }
}
