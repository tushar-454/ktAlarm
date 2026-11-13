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
import android.media.AudioManager
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
 *
 * Mission alarms are stricter: no Dismiss action, no auto-silence (only a [MISSION_CAP_MS]
 * safety cap), the alarm-stream volume can't be turned down, and they come back after a
 * reboot or a service kill (see [PendingRing]).
 */
class AlarmService : Service() {

  companion object {
    const val ACTION_RING = "expo.modules.ktalarm.ACTION_RING"
    /** Re-ring an interrupted mission alarm without advancing it again. */
    const val ACTION_RESUME = "expo.modules.ktalarm.ACTION_RESUME"
    /** Lower / restore the sound while the user works on a mission. */
    const val ACTION_DUCK = "expo.modules.ktalarm.ACTION_DUCK"
    const val ACTION_UNDUCK = "expo.modules.ktalarm.ACTION_UNDUCK"
    private const val TAG = "KtAlarm"
    private const val CHANNEL_RINGING = "kt_alarm_ringing"
    private const val CHANNEL_MISSED = "kt_alarm_missed"
    private const val NOTIFICATION_ID = 0x4B7A
    private const val AUTO_SILENCE_MS = 10 * 60 * 1000L
    private const val FADE_IN_MS = 20_000L
    /** A mission alarm gives up after this long, so a broken sensor can't ring forever. */
    const val MISSION_CAP_MS = 60 * 60 * 1000L
    private const val DUCK_GAIN = 0.3f
    /** Mission alarms ring at least this loud (fraction of the alarm stream's max volume). */
    private const val MISSION_MIN_VOLUME = 0.6f

    /** Id of the alarm currently ringing, or null. */
    @Volatile
    var activeAlarmId: String? = null
      private set

    /** Ducks or restores the ringing sound. No-op when nothing is ringing. */
    fun setDucked(context: Context, ducked: Boolean) {
      if (activeAlarmId == null) return
      try {
        context.startService(
          Intent(context, AlarmService::class.java).setAction(if (ducked) ACTION_DUCK else ACTION_UNDUCK),
        )
      } catch (e: IllegalStateException) {
        Log.w(TAG, "Cannot change ringing volume", e)
      }
    }
  }

  private val handler = Handler(Looper.getMainLooper())
  private var player: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var fadeStartedAt = 0L
  private var ducked = false

  private val audio: AudioManager by lazy { getSystemService(AudioManager::class.java) }
  /** User's alarm volume before we raised it, or -1. */
  private var originalVolume = -1
  /** Alarm-stream volume we hold while a mission alarm rings, or -1. */
  private var guardedVolume = -1

  private val autoSilence = Runnable {
    val id = activeAlarmId ?: return@Runnable
    Log.i(TAG, "Auto-silencing $id")
    AlarmStore.get(this, id)?.let { postMissedNotification(it) }
    AlarmController.release(this, id)
  }

  /** Volume keys pressed while the screen is off still reach the system. Undo them. */
  private val volumeGuard = object : Runnable {
    override fun run() {
      if (guardedVolume < 0) return
      try {
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < guardedVolume) {
          audio.setStreamVolume(AudioManager.STREAM_ALARM, guardedVolume, 0)
        }
      } catch (e: SecurityException) {
        Log.w(TAG, "Cannot hold alarm volume", e)
        return
      }
      handler.postDelayed(this, 400)
    }
  }

  private val fadeStep = object : Runnable {
    override fun run() {
      val p = player ?: return
      if (ducked) return
      val progress = ((System.currentTimeMillis() - fadeStartedAt).toFloat() / FADE_IN_MS).coerceIn(0f, 1f)
      val volume = 0.15f + 0.85f * progress
      try { p.setVolume(volume, volume) } catch (_: IllegalStateException) { return }
      if (progress < 1f) handler.postDelayed(this, 500)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action

    if (action == ACTION_DUCK || action == ACTION_UNDUCK) {
      // Plain startService() on the running service; no startForeground() needed.
      if (activeAlarmId == null) stopSelf() else applyDuck(action == ACTION_DUCK)
      return START_NOT_STICKY
    }

    if (intent == null) {
      // Killed while ringing and restarted by the system (START_STICKY). Re-ring through an
      // alarm-clock alarm, which is allowed to start a foreground service from the background.
      AlarmStore.getPendingRing(this)?.let {
        AlarmScheduler.scheduleResume(this, it.alarmId, System.currentTimeMillis() + 1_000)
      }
      stopSelf()
      return START_NOT_STICKY
    }

    // Must call startForeground() promptly on every start, before anything can fail.
    val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
    val alarm = when {
      id == null -> null
      action == ACTION_RING -> AlarmController.onFired(this, id)
      action == ACTION_RESUME -> resumable(id)
      else -> null
    }

    val shown = alarm ?: activeAlarmId?.let { AlarmStore.get(this, it) }
    startInForeground(buildRingingNotification(shown))

    when {
      alarm == null -> if (activeAlarmId == null) stopSelf()
      action == ACTION_RESUME && activeAlarmId == alarm.id -> Unit // already ringing
      else -> startRinging(alarm, resumed = action == ACTION_RESUME)
    }
    return if (activeAlarmId != null && currentAlarm()?.hasMission == true) START_STICKY else START_NOT_STICKY
  }

  private fun currentAlarm(): Alarm? = activeAlarmId?.let { AlarmStore.get(this, it) }

  /** The interrupted mission alarm, if it is still within its safety cap. */
  private fun resumable(id: String): Alarm? {
    val pending = AlarmStore.getPendingRing(this)?.takeIf { it.alarmId == id } ?: return null
    val alarm = AlarmStore.get(this, id)
    if (alarm?.hasMission != true || System.currentTimeMillis() - pending.startedAt >= MISSION_CAP_MS) {
      AlarmStore.clearPendingRing(this)
      return null
    }
    return alarm
  }

  private fun startRinging(alarm: Alarm, resumed: Boolean) {
    val current = currentAlarm()
    if (current != null && current.id != alarm.id && current.hasMission && !alarm.hasMission) {
      // A plain alarm must not cut a mission alarm short. Report it as missed instead.
      postMissedNotification(alarm)
      startInForeground(buildRingingNotification(current))
      return
    }

    // A second alarm going off while one is ringing replaces it.
    stopFeedback()
    activeAlarmId = alarm.id
    ducked = false

    val now = System.currentTimeMillis()
    val silenceAfter = if (alarm.hasMission) {
      val pending = AlarmStore.getPendingRing(this)?.takeIf { resumed && it.alarmId == alarm.id }
      val startedAt = pending?.startedAt ?: now
      // Keep the very first "original" volume if one mission alarm replaces another.
      val original = pending?.originalVolume?.takeIf { it >= 0 }
        ?: originalVolume.takeIf { it >= 0 }
        ?: currentAlarmVolume()
      AlarmStore.setPendingRing(this, PendingRing(alarm.id, startedAt, original))
      holdVolume(original)
      (MISSION_CAP_MS - (now - startedAt)).coerceAtLeast(60_000L)
    } else {
      releaseVolume()
      AUTO_SILENCE_MS
    }

    acquireWakeLock(silenceAfter + 60_000)
    startSound()
    if (alarm.vibrate) startVibration()
    launchRingingScreen(alarm)

    handler.removeCallbacks(autoSilence)
    handler.postDelayed(autoSilence, silenceAfter)

    AlarmEvents.emit(AlarmEvents.Type.RINGING_CHANGED)
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    stopFeedback()
    releaseVolume()
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
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
    val b = builder(CHANNEL_RINGING)
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle(title)
      .setContentText(alarm?.mission?.let { "$time · ${it.describe()} to turn off" } ?: time)
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
      b.setFullScreenIntent(fullScreen, true).setContentIntent(fullScreen)
      if (alarm.canSnooze) b.addAction(action(alarm, AlarmActionReceiver.ACTION_SNOOZE, "Snooze", 3))
      // Mission alarms can only be turned off from the mission screen.
      if (!alarm.hasMission) b.addAction(action(alarm, AlarmActionReceiver.ACTION_DISMISS, "Dismiss", 4))
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

  private fun acquireWakeLock(timeoutMs: Long) {
    val lock = wakeLock ?: getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ktalarm:ringing")
      .apply { setReferenceCounted(false) }
    wakeLock = lock
    lock.acquire(timeoutMs) // re-acquiring a non-counted lock just extends the timeout
  }

  private fun currentAlarmVolume(): Int =
    try { audio.getStreamVolume(AudioManager.STREAM_ALARM) } catch (_: Exception) { -1 }

  /** Raise the alarm stream to at least [MISSION_MIN_VOLUME] and keep it there. */
  private fun holdVolume(original: Int) {
    originalVolume = original
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    val floor = kotlin.math.ceil(max * MISSION_MIN_VOLUME).toInt()
    guardedVolume = maxOf(original, floor).coerceAtMost(max)
    handler.removeCallbacks(volumeGuard)
    handler.post(volumeGuard)
  }

  private fun releaseVolume() {
    handler.removeCallbacks(volumeGuard)
    guardedVolume = -1
    if (originalVolume >= 0) {
      try {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
      } catch (e: SecurityException) {
        Log.w(TAG, "Cannot restore alarm volume", e)
      }
    }
    originalVolume = -1
  }

  private fun applyDuck(duck: Boolean) {
    ducked = duck
    handler.removeCallbacks(fadeStep)
    val gain = if (duck) DUCK_GAIN else 1f
    try { player?.setVolume(gain, gain) } catch (_: IllegalStateException) {}
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
