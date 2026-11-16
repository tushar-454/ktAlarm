package expo.modules.ktalarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/** Independent, user-started sleep playback. It never edits or cancels a scheduled wake alarm. */
class SleepSoundService : Service() {
  companion object {
    private const val CHANNEL = "kt_sleep_sounds"
    private const val NOTIFICATION = 0x4B80
    private const val TIMER_REQUEST = 0x4B81
    const val ACTION_STOP = "expo.modules.ktalarm.STOP_SLEEP_SOUND"
    private var instance: SleepSoundService? = null

    internal fun start(context: Context, selection: SleepSelection) {
      check(AlarmService.activeAlarmId == null) { "Dismiss the ringing alarm before starting sleep sounds." }
      val intent = Intent(context, SleepSoundService::class.java)
        .putExtra("uri", selection.uri).putExtra("name", selection.name)
        .putExtra("minutes", selection.minutes).putExtra("alarmId", selection.alarmId)
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    /** All callers run on main, including the alarm service, timer receiver and JS functions. */
    internal fun stop(context: Context) {
      val service = instance
      if (service != null) service.finishPlayback() else context.stopService(Intent(context, SleepSoundService::class.java))
    }
  }

  private val handler = Handler(Looper.getMainLooper())
  private var player: MediaPlayer? = null
  private var focusRequest: AudioFocusRequest? = null
  private val audio by lazy { getSystemService(AudioManager::class.java) }
  private val manager by lazy { getSystemService(AlarmManager::class.java) }
  private var noisyRegistered = false
  private val timeout = Runnable { finishPlayback() }
  private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
    if (change < 0) finishPlayback()
  }
  private val noisy = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = finishPlayback()
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null) { stopSelf(); return START_NOT_STICKY }
    val uri = intent.getStringExtra("uri") ?: run { stopSelf(); return START_NOT_STICKY }
    val selection = SleepSelection(uri, intent.getStringExtra("name") ?: "Sleep sound",
      intent.getIntExtra("minutes", 30).coerceIn(1, 720), intent.getStringExtra("alarmId"))
    ensureChannel()
    val preparing = notification(selection, null)
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIFICATION, preparing, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } else startForeground(NOTIFICATION, preparing)
    releasePlayer()
    if (AlarmService.activeAlarmId != null) { finishPlayback(); return START_NOT_STICKY }
    SleepSoundStore.error = null
    SleepSoundStore.current = SleepPlayback(selection, null, true)
    SleepSoundStore.save(this, selection)
    SleepSoundStore.changed()
    try {
      val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
      val focus = if (Build.VERSION.SDK_INT >= 26) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener, handler).build()
        focusRequest = request
        audio.requestAudioFocus(request)
      } else {
        @Suppress("DEPRECATION")
        audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
      }
      check(focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio is busy. Try again after other playback or a call ends." }
      val p = MediaPlayer()
      player = p
      p.setAudioAttributes(attrs)
      p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
      p.isLooping = true
      p.setVolume(0.5f, 0.5f)
      p.setDataSource(this, PresetMedia.audioUri(this, selection.uri))
      p.setOnErrorListener { _, _, _ ->
        if (player === p) fail("Could not play this sleep sound. Choose another audio file.")
        true
      }
      p.setOnPreparedListener {
        if (player !== p) return@setOnPreparedListener
        if (AlarmService.activeAlarmId != null) { finishPlayback(); return@setOnPreparedListener }
        try {
          p.start()
          val deadline = SleepDeadline(SystemClock.elapsedRealtime(), System.currentTimeMillis(), selection.minutes)
          SleepSoundStore.current = SleepPlayback(selection, deadline.endWall, false)
          handler.removeCallbacks(timeout)
          handler.postDelayed(timeout, deadline.durationMs)
          scheduleStop(deadline.endElapsed)
          getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(selection, deadline.endWall))
          registerNoisyReceiver()
          SleepSoundStore.changed()
        } catch (e: Exception) { fail(e.message ?: "Could not start playback.") }
      }
      // Also cap preparation; an unresponsive decoder must not leave a foreground service behind.
      handler.postDelayed(timeout, 30_000)
      p.prepareAsync()
    } catch (e: Exception) { fail(e.message ?: "Could not play this sound.") }
    return START_NOT_STICKY
  }

  private fun stopIntent(request: Int): PendingIntent = PendingIntent.getBroadcast(this, request,
    Intent(this, SleepSoundStopReceiver::class.java).setAction(ACTION_STOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  private fun scheduleStop(elapsed: Long) {
    val pi = stopIntent(TIMER_REQUEST)
    if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
      manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, pi)
    } else manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, pi)
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(CHANNEL, "Sleep sounds", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Playing relaxing sounds until your sleep timer ends"
        setSound(null, null)
      })
  }

  private fun notification(selection: SleepSelection, endsAt: Long?): Notification {
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else {
      @Suppress("DEPRECATION")
      Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
    }
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    return builder.setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(selection.name)
      .setContentText(if (endsAt == null) "Preparing sleep sound…" else "Sleep timer · ${selection.minutes} min")
      .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_TRANSPORT)
      .apply {
        if (launch != null) setContentIntent(PendingIntent.getActivity(this@SleepSoundService, 0x4B84, launch,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (endsAt != null) setWhen(endsAt).setUsesChronometer(true).setChronometerCountDown(true)
      }
      .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stopIntent(0x4B82)).build())
      .build()
  }

  private fun registerNoisyReceiver() {
    if (noisyRegistered) return
    val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
    else {
      @Suppress("DEPRECATION")
      registerReceiver(noisy, filter)
    }
    noisyRegistered = true
  }

  private fun fail(message: String) {
    Log.w("KtSleepSound", message)
    SleepSoundStore.error = message
    finishPlayback()
  }

  private fun releasePlayer() {
    handler.removeCallbacksAndMessages(null)
    manager.cancel(stopIntent(TIMER_REQUEST))
    player?.let { p ->
      player = null
      p.setOnPreparedListener(null)
      p.setOnErrorListener(null)
      p.release()
    }
    if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
    else {
      @Suppress("DEPRECATION")
      audio.abandonAudioFocus(focusListener)
    }
    focusRequest = null
    if (noisyRegistered) { unregisterReceiver(noisy); noisyRegistered = false }
  }

  private fun finishPlayback() {
    releasePlayer()
    SleepSoundStore.current = null
    SleepSoundStore.changed()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    releasePlayer()
    if (instance === this) {
      instance = null
      SleepSoundStore.current = null
      SleepSoundStore.changed()
    }
    super.onDestroy()
  }
}

class SleepSoundStopReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == SleepSoundService.ACTION_STOP) SleepSoundService.stop(context)
  }
}
