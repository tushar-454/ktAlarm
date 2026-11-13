package expo.modules.ktalarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import java.text.DateFormat
import java.util.Date

/**
 * Foreground service that does the actual ringing:
 *  - loops the system alarm tone on the ALARM audio stream (rings even in silent mode)
 *  - fades the volume in
 *  - vibrates
 *  - holds a wake lock
 *  - posts a full-screen-intent notification that turns on the screen and shows
 *    [AlarmActivity] over the lock screen
 *  - auto-silences after [AUTO_SILENCE_MS]
 */
class AlarmService : Service() {

  companion object {
    const val ACTION_RING = "expo.modules.ktalarm.ACTION_RING"
    private const val TAG = "KtAlarm"
    private const val CHANNEL_RINGING = "kt_alarm_ringing"
    private const val CHANNEL_MISSED = "kt_alarm_missed"
    private const val NOTIFICATION_ID = 0x4B7A
    private const val AUTO_SILENCE_MS = 10 * 60 * 1000L
    private const val FADE_IN_MS = 20_000L

    /** Id of the alarm currently ringing, or null. */
    @Volatile
    var activeAlarmId: String? = null
      private set
  }

  private val handler = Handler(Looper.getMainLooper())
  private var player: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var fadeStartedAt = 0L

  private val autoSilence = Runnable {
    val id = activeAlarmId ?: return@Runnable
    Log.i(TAG, "Auto-silencing $id")
    AlarmStore.get(this, id)?.let { postMissedNotification(it) }
    AlarmController.dismiss(this, id)
  }

  private val fadeStep = object : Runnable {
    override fun run() {
      val p = player ?: return
      val progress = ((System.currentTimeMillis() - fadeStartedAt).toFloat() / FADE_IN_MS).coerceIn(0f, 1f)
      val volume = 0.15f + 0.85f * progress
      try { p.setVolume(volume, volume) } catch (_: IllegalStateException) { return }
      if (progress < 1f) handler.postDelayed(this, 500)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Must call startForeground() promptly on every start, before anything can fail.
    val id = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
    val alarm = if (intent?.action == ACTION_RING && id != null) AlarmController.onFired(this, id) else null

    val shown = alarm ?: activeAlarmId?.let { AlarmStore.get(this, it) }
    startInForeground(buildRingingNotification(shown))

    if (alarm == null) {
      if (activeAlarmId == null) stopSelf()
      return START_NOT_STICKY
    }

    // A second alarm going off while one is ringing replaces it.
    stopFeedback()
    activeAlarmId = alarm.id

    acquireWakeLock()
    startSound()
    if (alarm.vibrate) startVibration()
    launchRingingScreen(alarm)

    handler.removeCallbacks(autoSilence)
    handler.postDelayed(autoSilence, AUTO_SILENCE_MS)

    AlarmEvents.emit(AlarmEvents.Type.RINGING_CHANGED)
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    stopFeedback()
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    activeAlarmId = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    }
    AlarmEvents.emit(AlarmEvents.Type.RINGING_CHANGED)
    super.onDestroy()
  }

  // region Foreground + notification

  private fun startInForeground(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      // Android 14+: "systemExempted" is meant for alarm apps holding exact-alarm access.
      val am = getSystemService(AlarmManager::class.java)
      val type = if (am.canScheduleExactAlarms()) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
      } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
      }
      startForeground(NOTIFICATION_ID, notification, type)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun ensureChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_RINGING) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_RINGING, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "Shown while an alarm is ringing"
          setSound(null, null) // the service plays the sound itself on the alarm stream
          enableVibration(false)
          setBypassDnd(true)
          lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        },
      )
    }
    if (nm.getNotificationChannel(CHANNEL_MISSED) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_MISSED, "Missed alarms", NotificationManager.IMPORTANCE_DEFAULT),
      )
    }
  }

  private fun builder(channel: String): Notification.Builder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, channel)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this).setPriority(Notification.PRIORITY_MAX)
    }

  private fun buildRingingNotification(alarm: Alarm?): Notification {
    ensureChannels()
    val title = alarm?.label?.takeIf { it.isNotBlank() } ?: "Alarm"
    val b = builder(CHANNEL_RINGING)
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle(title)
      .setContentText(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
      .setCategory(Notification.CATEGORY_ALARM)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setAutoCancel(false)
      .setShowWhen(false)

    if (alarm != null) {
      val fullScreen = PendingIntent.getActivity(
        this,
        AlarmScheduler.requestCode(alarm.id, 2),
        AlarmActivity.intent(this, alarm.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      b.setFullScreenIntent(fullScreen, true)
        .setContentIntent(fullScreen)
        .addAction(action(alarm, AlarmActionReceiver.ACTION_SNOOZE, "Snooze", 3))
        .addAction(action(alarm, AlarmActionReceiver.ACTION_DISMISS, "Dismiss", 4))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    }
    return b.build()
  }

  private fun action(alarm: Alarm, actionName: String, title: String, slot: Int): Notification.Action {
    val pi = PendingIntent.getBroadcast(
      this,
      AlarmScheduler.requestCode(alarm.id, slot),
      Intent(this, AlarmActionReceiver::class.java)
        .setAction(actionName)
        .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Action.Builder(
      Icon.createWithResource(this, android.R.drawable.ic_lock_idle_alarm),
      title,
      pi,
    ).build()
  }

  private fun postMissedNotification(alarm: Alarm) {
    ensureChannels()
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val content = launch?.let {
      PendingIntent.getActivity(this, AlarmScheduler.requestCode(alarm.id, 5), it, PendingIntent.FLAG_IMMUTABLE)
    }
    val n = builder(CHANNEL_MISSED)
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle("Missed alarm")
      .setContentText(alarm.label.ifBlank { String.format("%02d:%02d", alarm.hour, alarm.minute) })
      .setAutoCancel(true)
      .apply { if (content != null) setContentIntent(content) }
      .build()
    try {
      getSystemService(NotificationManager::class.java).notify(alarm.id.hashCode(), n)
    } catch (e: SecurityException) {
      Log.w(TAG, "Cannot post missed-alarm notification", e)
    }
  }

  // endregion

  // region Ringing screen

  private fun launchRingingScreen(alarm: Alarm) {
    // With "Display over other apps" granted we may start the activity directly from the
    // background, which also covers the case where the screen is already on and unlocked
    // (the full-screen intent would only show a heads-up there).
    // Otherwise the full-screen intent on the notification wakes the screen and shows it.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
      try {
        startActivity(AlarmActivity.intent(this, alarm.id))
      } catch (e: Exception) {
        Log.w(TAG, "Direct activity start blocked, relying on full-screen intent", e)
      }
    }
  }

  // endregion

  // region Sound / vibration / wake lock

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(PowerManager::class.java)
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ktalarm:ringing").apply {
      setReferenceCounted(false)
      acquire(AUTO_SILENCE_MS + 60_000)
    }
  }

  private fun alarmUris(): List<Uri> = listOfNotNull(
    RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
    Settings.System.DEFAULT_ALARM_ALERT_URI,
    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
  ).distinct()

  private fun startSound() {
    val attrs = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ALARM)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()

    for (uri in alarmUris()) {
      val p = MediaPlayer()
      try {
        p.setAudioAttributes(attrs)
        p.setDataSource(this, uri)
        p.isLooping = true
        p.prepare()
        p.setVolume(0.15f, 0.15f)
        p.start()
        player = p
        fadeStartedAt = System.currentTimeMillis()
        handler.post(fadeStep)
        return
      } catch (e: Exception) {
        Log.w(TAG, "Could not play $uri, trying next", e)
        p.release()
      }
    }
    Log.e(TAG, "No playable alarm sound found")
  }

  private fun startVibration() {
    val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (!v.hasVibrator()) return
    vibrator = v
    val pattern = longArrayOf(0, 800, 600)
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        v.vibrate(
          VibrationEffect.createWaveform(pattern, 0),
          VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
        @Suppress("DEPRECATION")
        v.vibrate(
          VibrationEffect.createWaveform(pattern, 0),
          AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
      }
      else -> {
        @Suppress("DEPRECATION")
        v.vibrate(pattern, 0)
      }
    }
  }

  private fun stopFeedback() {
    handler.removeCallbacks(fadeStep)
    player?.let {
      try { if (it.isPlaying) it.stop() } catch (_: IllegalStateException) {}
      it.release()
    }
    player = null
    vibrator?.cancel()
    vibrator = null
  }

  // endregion
}
