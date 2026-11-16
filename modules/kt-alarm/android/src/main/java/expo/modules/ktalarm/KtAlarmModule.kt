package expo.modules.ktalarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
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
  @Field val ringtoneUri: String? = null,
  @Field val ringtoneName: String? = null,
  @Field val backgroundUri: String? = null,
  @Field val backgroundName: String? = null,
  @Field val sleepSoundUri: String? = null,
  @Field val sleepSoundName: String? = null,
  @Field val sleepSoundMinutes: Int = 30,
) : Record

data class SleepSoundInput(
  @Field val uri: String = "preset:pink",
  @Field val name: String = "Pink noise",
  @Field val minutes: Int = 30,
  @Field val alarmId: String? = null,
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
  CodedException("ERR_CAMERA_PERMISSION", "Camera permission is needed for this mission.", null)

class NoActivityException : CodedException("ERR_NO_ACTIVITY", "The app is not in the foreground.", null)

class KtAlarmModule : Module() {

  companion object {
    private const val REQUEST_SCAN = 0x4B7A
    private const val REQUEST_MEDIA = 0x4B7B
  }

  /** The JS promise waiting for [ScanActivity]'s result. */
  private var pendingScan: Promise? = null
  private var pendingMedia: Promise? = null
  private var mediaKind = "image"

  private val context: Context
    get() = appContext.reactContext?.applicationContext ?: throw Exceptions.ReactContextLost()

  /** Settings screens should be opened from the activity so they return to the app. */
  private val launcherContext: Context
    get() = appContext.currentActivity ?: context

  private val bridgeListener = AlarmEvents.Listener { type, ringingId ->
    when (type) {
      AlarmEvents.Type.ALARMS_CHANGED -> sendEvent("onAlarmsChanged", mapOf<String, Any?>())
      AlarmEvents.Type.RINGING_CHANGED -> sendEvent("onRingingChanged", mapOf("alarmId" to ringingId))
      AlarmEvents.Type.SLEEP_SOUND_CHANGED -> sendEvent("onSleepSoundChanged", SleepSoundStore.state(context))
    }
  }

  override fun definition() = ModuleDefinition {
    Name("KtAlarm")

    Events("onAlarmsChanged", "onRingingChanged", "onSleepSoundChanged")

    View(AlarmTimePickerView::class) {
      Events("onTimeChanged")
      Prop("initialHour") { view: AlarmTimePickerView, value: Int -> view.setInitialHour(value) }
      Prop("initialMinute") { view: AlarmTimePickerView, value: Int -> view.setInitialMinute(value) }
      Prop("dark") { view: AlarmTimePickerView, value: Boolean -> view.setDark(value) }
    }

    OnActivityResult { _, payload ->
      if (payload.requestCode == REQUEST_MEDIA) {
        val promise = pendingMedia ?: return@OnActivityResult
        pendingMedia = null
        val uri = payload.data?.data
        if (payload.resultCode != Activity.RESULT_OK || uri == null) {
          promise.resolve(null)
        } else {
          val ctx = context
          val kind = mediaKind
          AlarmMediaStore.io.execute {
            try {
              promise.resolve(AlarmMediaStore.importMedia(ctx, uri, kind))
            } catch (e: Exception) {
              promise.reject("ERR_MEDIA_IMPORT", e.message ?: "Could not import this file.", e)
            }
          }
        }
        return@OnActivityResult
      }
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
    OnDestroy {
      AlarmEvents.remove(bridgeListener)
      pendingMedia?.resolve(null)
      pendingMedia = null
    }

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
      val missions = input.missions.map { it.toMission() ?: throw InvalidMissionException() }
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
        ringtoneUri = AlarmMediaStore.checkedUri(context, input.ringtoneUri),
        ringtoneName = input.ringtoneName?.take(120),
        backgroundUri = AlarmMediaStore.checkedUri(context, input.backgroundUri, "background"),
        backgroundName = input.backgroundName?.take(120),
        sleepSoundUri = AlarmMediaStore.checkedUri(context, input.sleepSoundUri, "sleep"),
        sleepSoundName = input.sleepSoundName?.take(120),
        sleepSoundMinutes = input.sleepSoundMinutes.coerceIn(1, 720),
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

    AsyncFunction("pickAlarmMedia") { kind: String, promise: Promise ->
      require(kind == "audio" || kind == "image") { "Unsupported media type." }
      val activity = appContext.currentActivity ?: throw NoActivityException()
      check(pendingMedia == null) { "A file picker is already open." }
      pendingMedia = promise
      mediaKind = kind
      Handler(Looper.getMainLooper()).post {
        try {
          // The system grants read access to this file. No broad storage/media permission needed.
          val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (kind == "image") "image/*" else "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          @Suppress("DEPRECATION")
          activity.startActivityForResult(intent, REQUEST_MEDIA)
        } catch (e: Exception) {
          pendingMedia = null
          promise.reject("ERR_MEDIA_PICKER", "Could not open the system file picker.", e)
        }
      }
    }

    Function("getSleepSoundState") { SleepSoundStore.state(context) }
    AsyncFunction("startSleepSound") { input: SleepSoundInput ->
      require(input.minutes in 1..720) { "Choose a duration between 1 and 720 minutes." }
      val uri = requireNotNull(AlarmMediaStore.checkedUri(context, input.uri, "sleep"))
      SleepSoundService.start(launcherContext,
        SleepSelection(uri, input.name.take(120), input.minutes, input.alarmId))
    }.runOnQueue(Queues.MAIN)
    AsyncFunction("stopSleepSound") { SleepSoundService.stop(context) }.runOnQueue(Queues.MAIN)

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

    /** Squat and barcode missions use the camera. Resolves `{ granted }` (plus Expo's status fields). */
    AsyncFunction("requestCameraPermission") { promise: Promise ->
      Permissions.askForPermissionsWithPermissionsManager(appContext.permissions, promise, Manifest.permission.CAMERA)
    }

    AsyncFunction("requestActivityRecognitionPermission") { promise: Promise ->
      if (Build.VERSION.SDK_INT >= 29) Permissions.askForPermissionsWithPermissionsManager(
        appContext.permissions, promise, Manifest.permission.ACTIVITY_RECOGNITION)
      else promise.resolve(mapOf("granted" to true))
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
