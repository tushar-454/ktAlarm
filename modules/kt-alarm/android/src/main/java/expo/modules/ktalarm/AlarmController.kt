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
    save(context, alarm.copy(enabled = enabled, snoozeUntil = null, snoozeCount = 0))
  }

  /**
   * Called the moment an alarm goes off. Advances the alarm right away, so a crash or
   * reboot while ringing can never make it ring again for the same occurrence.
   * (A mission alarm interrupted by a reboot is brought back via [PendingRing] instead.)
   */
  fun onFired(context: Context, id: String): Alarm? {
    val alarm = AlarmStore.get(context, id) ?: return null
    // A snooze firing is the same occurrence: keep counting its snoozes. A fresh one starts at 0.
    val fromSnooze = alarm.snoozeUntil != null
    val advanced = alarm.copy(
      enabled = alarm.isRepeating && alarm.enabled,
      snoozeUntil = null,
      snoozeCount = if (fromSnooze) alarm.snoozeCount else 0,
    )
    AlarmStore.upsert(context, advanced)
    if (advanced.isRepeating && advanced.enabled) {
      // Compute from one minute ahead so we never re-arm the occurrence that is ringing now.
      AlarmScheduler.schedule(context, advanced, advanced.nextTrigger(System.currentTimeMillis() + 60_000))
    }
    AlarmEvents.emit(AlarmEvents.Type.ALARMS_CHANGED)
    return advanced
  }

  /** @return false when the alarm has used up its snoozes (it keeps ringing). */
  fun snooze(context: Context, id: String): Boolean {
    val alarm = AlarmStore.get(context, id)
    if (alarm != null) {
      if (!alarm.canSnooze) return false
      val until = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
      save(context, alarm.copy(enabled = true, snoozeUntil = until, snoozeCount = alarm.snoozeCount + 1))
    }
    if (AlarmService.activeAlarmId == id) stopRinging(context)
    return true
  }

  /**
   * Plain dismiss (button, notification, JS). Refused for a locked mission alarm.
   * @return false when refused.
   */
  fun dismiss(context: Context, id: String): Boolean {
    val alarm = AlarmStore.get(context, id)
    if (alarm != null && alarm.isLocked()) return false
    release(context, id)
    return true
  }

  /** The user finished the mission. */
  fun completeMission(context: Context, id: String) = release(context, id)

  /**
   * Turns the alarm off unconditionally: mission completed, safety cap reached, or no mission.
   * Cancels a pending snooze and resets the snooze counter.
   */
  fun release(context: Context, id: String) {
    val alarm = AlarmStore.get(context, id)
    if (alarm != null && (alarm.snoozeUntil != null || alarm.snoozeCount != 0)) {
      val wasSnoozed = alarm.snoozeUntil != null
      save(
        context,
        alarm.copy(
          snoozeUntil = null,
          snoozeCount = 0,
          // Dismissing a snoozed one-shot alarm switches it off.
          enabled = if (wasSnoozed) alarm.isRepeating && alarm.enabled else alarm.enabled,
        ),
      )
    }
    if (AlarmService.activeAlarmId == id) {
      stopRinging(context)
    } else if (AlarmStore.getPendingRing(context)?.alarmId == id) {
      AlarmStore.clearPendingRing(context)
    }
  }

  fun stopRinging(context: Context) {
    AlarmStore.clearPendingRing(context)
    AlarmScheduler.cancelResume(context)
    context.stopService(Intent(context, AlarmService::class.java))
  }
}
