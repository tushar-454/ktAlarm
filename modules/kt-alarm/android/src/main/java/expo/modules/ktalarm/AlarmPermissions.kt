package expo.modules.ktalarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Everything an alarm needs to survive app-kill, Doze and the lock screen on Android 7–16,
 * plus shortcuts to the relevant system settings screens.
 */
object AlarmPermissions {

  fun status(context: Context): Map<String, Boolean> = mapOf(
    "notifications" to hasNotifications(context),
    "exactAlarm" to hasExactAlarm(context),
    "fullScreenIntent" to hasFullScreenIntent(context),
    "batteryOptimizationIgnored" to isIgnoringBatteryOptimizations(context),
    "overlay" to hasOverlay(context),
    "camera" to CameraView.hasCameraPermission(context),
  )

  fun hasNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    return context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
  }

  fun hasExactAlarm(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
      context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

  /** Android 14+ lets the user revoke full-screen intents; without it the screen won't wake. */
  fun hasFullScreenIntent(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
      context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

  fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

  fun hasOverlay(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

  // region Settings screens

  private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

  fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      launch(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
    } else {
      openAppDetails(context)
    }
  }

  fun openFullScreenIntentSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      launch(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context)))
    } else {
      openNotificationSettings(context)
    }
  }

  @Suppress("BatteryLife") // An alarm clock is an accepted use case for this exemption.
  fun requestIgnoreBatteryOptimizations(context: Context) {
    if (!launch(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)))) {
      launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
  }

  fun openOverlaySettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      launch(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)))
    }
  }

  fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
    }
    launch(context, intent)
  }

  fun openAppDetails(context: Context) {
    launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
  }

  /**
   * Xiaomi, Oppo, Vivo, Huawei, Samsung, OnePlus… kill background apps aggressively and have their
   * own "auto-start" / "background activity" switches. Opens the right one if present.
   * @return true when a vendor screen was opened, false when it fell back to app details.
   */
  fun openAutoStartSettings(context: Context): Boolean {
    val candidates = listOf(
      "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
      "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
      "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
      "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
      "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
      "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
      "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
      "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
      "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
      "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
      "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
      "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
    )
    for ((pkg, cls) in candidates) {
      if (launch(context, Intent().setComponent(ComponentName(pkg, cls)), log = false)) return true
    }
    openAppDetails(context)
    return false
  }

  private fun launch(context: Context, intent: Intent, log: Boolean = true): Boolean = try {
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
  } catch (e: Exception) {
    if (log) Log.w("KtAlarm", "Cannot open ${intent.action ?: intent.component}", e)
    false
  }

  // endregion
}
