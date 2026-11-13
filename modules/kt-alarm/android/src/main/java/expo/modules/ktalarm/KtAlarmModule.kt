package expo.modules.ktalarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.util.UUID

data class MissionInput(
  @Field val type: String = "",
  @Field val difficulty: Int = 1,
  @Field val count: Int? = null,
  @Field val barcodeValue: String? = null,
  @Field val barcodeName: String? = null,
) : Record {
  fun toMission(): Mission? = Mission.create(type, difficulty, count, barcodeValue, barcodeName)
}

data class AlarmInput(
  @Field val id: String? = null,
  @Field val hour: Int = 7,
  @Field val minute: Int = 0,
  @Field val label: String = "",
  @Field val days: List<Int> = emptyList(),
  @Field val enabled: Boolean = true,
  @Field val vibrate: Boolean = true,
  @Field val snoozeMinutes: Int = 5,
  @Field val missions: List<MissionInput> = emptyList(),
  /** -1 = unlimited. */
  @Field val maxSnoozes: Int = Alarm.UNLIMITED_SNOOZES,
) : Record

class AlarmLockedException :
  CodedException("ERR_ALARM_LOCKED", "Complete the wake-up mission to turn off this alarm.", null)

class OverlayPermissionRequiredException :
  CodedException(
    "ERR_OVERLAY_REQUIRED",
    "Mission alarms need \"Display over other apps\" so the mission can't be skipped.",
    null,
  )

class InvalidMissionException :
  CodedException("ERR_INVALID_MISSION", "Unknown mission type, or a barcode mission without a registered code.", null)

class CameraPermissionException :
  CodedException("ERR_CAMERA_PERMISSION", "Camera permission is needed to register a code.", null)

class NoActivityException : CodedException("ERR_NO_ACTIVITY", "The app is not in the foreground.", null)

class KtAlarmModule : Module() {

  companion object {
    private const val REQUEST_SCAN = 0x4B7A
  }

  /** The JS promise waiting for [ScanActivity]'s result. */
  private var pendingScan: Promise? = null

  private val context: Context
    get() = appContext.reactContext?.applicationContext ?: throw Exceptions.ReactContextLost()

  /** Settings screens should be opened from the activity so they return to the app. */
  private val launcherContext: Context
    get() = appContext.currentActivity ?: context

  private val bridgeListener = AlarmEvents.Listener { type, ringingId ->
    when (type) {
      AlarmEvents.Type.ALARMS_CHANGED -> sendEvent("onAlarmsChanged", mapOf<String, Any?>())
      AlarmEvents.Type.RINGING_CHANGED -> sendEvent("onRingingChanged", mapOf("alarmId" to ringingId))
    }
  }

  override fun definition() = ModuleDefinition {
    Name("KtAlarm")

    Events("onAlarmsChanged", "onRingingChanged")

    OnActivityResult { _, payload ->
      if (payload.requestCode != REQUEST_SCAN) return@OnActivityResult
      val promise = pendingScan ?: return@OnActivityResult
      pendingScan = null
      val data = payload.data
      when {
        payload.resultCode == Activity.RESULT_OK && data != null -> promise.resolve(
          mapOf(
            "value" to data.getStringExtra(ScanActivity.EXTRA_VALUE),
            "format" to data.getStringExtra(ScanActivity.EXTRA_FORMAT),
          ),
        )
        data?.getStringExtra(ScanActivity.EXTRA_ERROR) == ScanActivity.ERROR_PERMISSION ->
          promise.reject(CameraPermissionException())
        else -> promise.resolve(null) // cancelled
      }
    }

    OnCreate { AlarmEvents.add(bridgeListener) }
    OnDestroy { AlarmEvents.remove(bridgeListener) }

    // region Alarms

    Function("getAlarms") {
      AlarmStore.all(context)
        .sortedWith(compareBy({ it.hour }, { it.minute }))
        .map { it.toMap() }
    }

    AsyncFunction("saveAlarm") { input: AlarmInput ->
      require(input.hour in 0..23 && input.minute in 0..59) { "Invalid time" }
      val existing = input.id?.let { AlarmStore.get(context, it) }
      if (existing?.isLocked() == true) throw AlarmLockedException()
      // One mission per alarm in v1; the list shape leaves room for chains.
      val missions = input.missions.take(1).map { it.toMission() ?: throw InvalidMissionException() }
      if (missions.isNotEmpty() && !AlarmPermissions.hasOverlay(context)) throw OverlayPermissionRequiredException()
      val alarm = Alarm(
        id = input.id ?: UUID.randomUUID().toString(),
        hour = input.hour,
        minute = input.minute,
        label = input.label.trim(),
        days = input.days.filter { it in 0..6 }.distinct().sorted(),
        enabled = input.enabled,
        vibrate = input.vibrate,
        snoozeMinutes = input.snoozeMinutes.coerceIn(1, 60),
        snoozeUntil = null,
        missions = missions,
        maxSnoozes = if (input.maxSnoozes < 0) Alarm.UNLIMITED_SNOOZES else input.maxSnoozes.coerceIn(0, 10),
        snoozeCount = 0,
      )
      AlarmController.save(context, alarm)
      alarm.toMap()
    }

    AsyncFunction("deleteAlarm") { id: String ->
      if (AlarmStore.get(context, id)?.isLocked() == true) throw AlarmLockedException()
      AlarmController.delete(context, id)
    }

    AsyncFunction("setAlarmEnabled") { id: String, enabled: Boolean ->
      if (!enabled && AlarmStore.get(context, id)?.isLocked() == true) throw AlarmLockedException()
      AlarmController.setEnabled(context, id, enabled)
    }

    /** @return false when no snoozes are left. */
    Function("snooze") { id: String -> AlarmController.snooze(context, id) }

    /** @return false for a locked mission alarm (it can only be turned off by the mission). */
    Function("dismiss") { id: String -> AlarmController.dismiss(context, id) }

    /** Runs a mission on the native alarm screen without ringing, to try it out. */
    Function("previewMission") { input: MissionInput ->
      val mission = input.toMission() ?: throw InvalidMissionException()
      launcherContext.startActivity(AlarmActivity.previewIntent(launcherContext, mission))
    }

    /** Opens the native scanner to register a barcode mission's code. Resolves null if cancelled. */
    AsyncFunction("scanBarcode") { promise: Promise ->
      val activity = appContext.currentActivity ?: throw NoActivityException()
      pendingScan?.resolve(null)
      pendingScan = promise
      Handler(Looper.getMainLooper()).post {
        @Suppress("DEPRECATION")
        activity.startActivityForResult(ScanActivity.intent(activity), REQUEST_SCAN)
      }
    }

    // endregion

    // region Uninstall protection

    Function("isUninstallProtectionEnabled") { UninstallProtection.isEnabled(context) }

    Function("enableUninstallProtection") { UninstallProtection.requestEnable(launcherContext) }

    AsyncFunction("disableUninstallProtection") {
      // Switching protection off is the first step to uninstalling, so not while a mission is due.
      if (AlarmStore.all(context).any { it.isLocked() }) throw AlarmLockedException()
      UninstallProtection.disable(context)
    }

    Function("getRingingAlarmId") { AlarmService.activeAlarmId }

    // endregion

    // region Permissions

    Function("getPermissionStatus") { AlarmPermissions.status(context) }

    AsyncFunction("requestNotificationPermission") { promise: Promise ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Permissions.askForPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.POST_NOTIFICATIONS,
        )
      } else {
        promise.resolve(mapOf("granted" to AlarmPermissions.hasNotifications(context)))
      }
    }

    Function("openExactAlarmSettings") { AlarmPermissions.openExactAlarmSettings(launcherContext) }
    Function("openFullScreenIntentSettings") { AlarmPermissions.openFullScreenIntentSettings(launcherContext) }
    Function("requestIgnoreBatteryOptimizations") { AlarmPermissions.requestIgnoreBatteryOptimizations(launcherContext) }
    Function("openOverlaySettings") { AlarmPermissions.openOverlaySettings(launcherContext) }
    Function("openNotificationSettings") { AlarmPermissions.openNotificationSettings(launcherContext) }
    Function("openAutoStartSettings") { AlarmPermissions.openAutoStartSettings(launcherContext) }

    // endregion
  }
}
