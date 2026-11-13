package expo.modules.ktalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Talks to [AlarmManager].
 *
 * Uses `setAlarmClock()`: the most reliable API Android has. It is exact, fires in Doze,
 * wakes the device, shows the alarm icon in the status bar, and lets the receiver start
 * a foreground service from the background.
 */
object AlarmScheduler {
  private const val TAG = "KtAlarm"

  fun schedule(context: Context, alarm: Alarm, triggerAt: Long = alarm.nextTrigger()) {
    cancel(context, alarm.id)
    if (!alarm.enabled) return

    val am = context.getSystemService(AlarmManager::class.java)
    val fire = firePendingIntent(context, alarm.id)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
      // Exact-alarm access was revoked (only possible on Android 12/12L). Best effort.
      Log.w(TAG, "Exact alarm permission missing, falling back to inexact alarm for ${alarm.id}")
      am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, fire)
      return
    }

    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showAppPendingIntent(context, alarm.id)), fire)
    Log.i(TAG, "Scheduled ${alarm.id} at $triggerAt")
  }

  fun cancel(context: Context, id: String) {
    val am = context.getSystemService(AlarmManager::class.java)
    am.cancel(firePendingIntent(context, id))
  }

  fun rescheduleAll(context: Context) {
    AlarmStore.all(context).forEach { schedule(context, it) }
  }

  private fun firePendingIntent(context: Context, id: String): PendingIntent {
    val intent = Intent(context, AlarmReceiver::class.java)
      .setAction(AlarmReceiver.ACTION_FIRE)
      .putExtra(EXTRA_ALARM_ID, id)
    return PendingIntent.getBroadcast(
      context,
      requestCode(id, 0),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /** Opened when the user taps the alarm icon / next-alarm text in the system UI. */
  private fun showAppPendingIntent(context: Context, id: String): PendingIntent {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: Intent().setPackage(context.packageName)
    return PendingIntent.getActivity(
      context,
      requestCode(id, 1),
      launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /** Stable, distinct request codes per alarm and per purpose. */
  fun requestCode(id: String, slot: Int): Int = id.hashCode() * 8 + slot

  const val EXTRA_ALARM_ID = "expo.modules.ktalarm.ALARM_ID"
}
