package expo.modules.ktalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Fired by AlarmManager at the exact alarm time. Hands off to the ringing service. */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val serviceAction = when (intent.action) {
      ACTION_FIRE -> AlarmService.ACTION_RING
      ACTION_RESUME -> AlarmService.ACTION_RESUME
      else -> return
    }
    val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
    Log.i("KtAlarm", "Alarm ${intent.action}: $id")

    // An alarm fired via setAlarmClock() is exempt from background FGS-start limits.
    val service = Intent(context, AlarmService::class.java)
      .setAction(serviceAction)
      .putExtra(AlarmScheduler.EXTRA_ALARM_ID, id)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(service)
    } else {
      context.startService(service)
    }
  }

  companion object {
    const val ACTION_FIRE = "expo.modules.ktalarm.ACTION_FIRE"
    const val ACTION_RESUME = "expo.modules.ktalarm.ACTION_RESUME"
  }
}

/** Snooze / Dismiss buttons in the ringing notification. */
class AlarmActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
    when (intent.action) {
      ACTION_SNOOZE -> AlarmController.snooze(context, id)
      // Mission alarms don't get this button; dismiss() refuses them anyway.
      ACTION_DISMISS -> AlarmController.dismiss(context, id)
    }
  }

  companion object {
    const val ACTION_SNOOZE = "expo.modules.ktalarm.ACTION_SNOOZE"
    const val ACTION_DISMISS = "expo.modules.ktalarm.ACTION_DISMISS"
  }
}

/**
 * AlarmManager forgets every alarm on reboot, and times shift after clock/timezone changes.
 * Re-arm everything from the native store.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    Log.i("KtAlarm", "Rescheduling alarms after ${intent.action}")
    AlarmScheduler.rescheduleAll(context)
    resumeInterruptedMission(context)
  }

  /** A mission alarm that was ringing when the phone went off rings again right away. */
  private fun resumeInterruptedMission(context: Context) {
    val pending = AlarmStore.getPendingRing(context) ?: return
    if (AlarmService.activeAlarmId != null) return // still ringing in this process (e.g. TIME_SET)
    val now = System.currentTimeMillis()
    val alarm = AlarmStore.get(context, pending.alarmId)
    if (alarm?.hasMission == true && now - pending.startedAt in 0 until AlarmService.MISSION_CAP_MS) {
      AlarmScheduler.scheduleResume(context, alarm.id, now + 1_500)
    } else {
      AlarmStore.clearPendingRing(context)
    }
  }
}
