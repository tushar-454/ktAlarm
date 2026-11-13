package expo.modules.ktalarm

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Opt-in "uninstall protection": while the app is an active device admin, Android refuses to
 * uninstall it until the user switches admin off in Settings first. Requests no admin policies.
 *
 * This is the strongest Play-Store-compatible option; it cannot stop a determined user, only
 * slow them down (and the mission screen comes back on top while an alarm is ringing).
 */
object UninstallProtection {
  private const val TAG = "KtAlarm"

  private fun component(context: Context) = ComponentName(context, UninstallProtectionReceiver::class.java)
  private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

  fun isEnabled(context: Context): Boolean = dpm(context)?.isAdminActive(component(context)) == true

  /** Opens the system "Activate device admin app?" screen. */
  fun requestEnable(context: Context) {
    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
      .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
      .putExtra(
        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
        "Stops ktAlerm from being uninstalled to skip a wake-up mission. " +
          "You can turn this off in the app at any time when no mission alarm is ringing.",
      )
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.w(TAG, "Cannot open device admin screen", e)
    }
  }

  fun disable(context: Context) {
    if (isEnabled(context)) dpm(context)?.removeActiveAdmin(component(context))
  }
}

class UninstallProtectionReceiver : DeviceAdminReceiver() {
  /**
   * The user is about to switch admin off in Settings. If a mission alarm is ringing, put the
   * mission back on top instead of letting them get to "Uninstall".
   */
  override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
    val id = AlarmService.activeAlarmId
    if (id != null && AlarmStore.get(context, id)?.hasMission == true) {
      try {
        context.startActivity(AlarmActivity.intent(context, id))
      } catch (e: Exception) {
        Log.w("KtAlarm", "Cannot bring the mission screen back", e)
      }
    }
    return "Without uninstall protection, ktAlerm can be uninstalled to skip a wake-up mission."
  }

  override fun onEnabled(context: Context, intent: Intent) = AlarmEvents.emit(AlarmEvents.Type.ALARMS_CHANGED)
  override fun onDisabled(context: Context, intent: Intent) = AlarmEvents.emit(AlarmEvents.Type.ALARMS_CHANGED)
}
