package expo.modules.ktalarm

import android.Manifest
import android.content.Context
import android.os.Build
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.util.UUID

data class AlarmInput(
  @Field val id: String? = null,
  @Field val hour: Int = 7,
  @Field val minute: Int = 0,
  @Field val label: String = "",
  @Field val days: List<Int> = emptyList(),
  @Field val enabled: Boolean = true,
  @Field val vibrate: Boolean = true,
  @Field val snoozeMinutes: Int = 5,
) : Record

class KtAlarmModule : Module() {

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
      )
      AlarmController.save(context, alarm)
      alarm.toMap()
    }

    AsyncFunction("deleteAlarm") { id: String -> AlarmController.delete(context, id) }

    AsyncFunction("setAlarmEnabled") { id: String, enabled: Boolean ->
      AlarmController.setEnabled(context, id, enabled)
    }

    Function("snooze") { id: String -> AlarmController.snooze(context, id) }

    Function("dismiss") { id: String -> AlarmController.dismiss(context, id) }

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
