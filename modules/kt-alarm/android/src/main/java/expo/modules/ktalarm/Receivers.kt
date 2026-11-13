package expo.modules.ktalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Fired by AlarmManager at the exact alarm time. Hands off to the ringing service. */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_FIRE) return
    val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
    Log.i("KtAlarm", "Alarm fired: $id")

    // An alarm fired via setAlarmClock() is exempt from background FGS-start limits.
    val service = Intent(context, AlarmService::class.java)
      .setAction(AlarmService.ACTION_RING)
      .putExtra(AlarmScheduler.EXTRA_ALARM_ID, id)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(service)
    } else {
      context.startService(service)
    }
  }

  companion object {
    const val ACTION_FIRE = "expo.modules.ktalarm.ACTION_FIRE"
  }
}

/** Snooze / Dismiss buttons in the ringing notification. */
class AlarmActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
    when (intent.action) {
      ACTION_SNOOZE -> AlarmController.snooze(context, id)
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
  }
}
