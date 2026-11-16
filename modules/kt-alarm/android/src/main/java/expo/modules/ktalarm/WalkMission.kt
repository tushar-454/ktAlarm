package expo.modules.ktalarm

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

internal class WalkMission(context: Context, private val mission: Mission, host: Host) :
  MissionRunner(context, host), SensorEventListener {
  override val title = "Walk"
  override val idleTimeoutMs = 60_000L
  private val sensors = context.getSystemService(SensorManager::class.java)
  private val counter = WalkCounter()
  private var listening = false
  private var done = false
  private var steps = 0
  private var lastDetectorStep = -1L
  private var listeningSince = 0L
  private val remaining = Ui.text(context, 96f, Color.WHITE, bold = true, value = mission.count.toString())
  private val bar = Ui.progressBar(context, mission.count)

  override val view: View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    addView(Ui.text(context, 20f, Ui.MUTED, value = "Carry your phone and walk"))
    addView(remaining, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    addView(Ui.text(context, 16f, Ui.MUTED, value = "steps left"))
    addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(32) })
    addView(Ui.text(context, 15f, Ui.MUTED,
      value = "Keep your phone in a trouser pocket and walk naturally.\nMoving the phone around is not a stride."),
      LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
  }

  override fun start() {
    if (done || listening) return
    counter.resetMotion()
    lastDetectorStep = -1L
    listeningSince = SystemClock.elapsedRealtime()
    val detector = if (hasPermission(context)) sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) else null
    listening = try {
      detector != null && sensors.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL, 0)
    } catch (_: SecurityException) { false }
    if (!listening) {
      val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
      listening = sensor != null && sensors.registerListener(this, sensor, 20_000, 0)
    }
    if (!listening) host.onMissionUnavailable()
  }

  override fun stop() {
    sensors.unregisterListener(this)
    listening = false
  }

  override fun onHostHidden() = stop()
  override fun onHostShown() = start()

  override fun onSensorChanged(event: SensorEvent) {
    if (done || !listening) return
    val now = event.timestamp / 1_000_000
    if (now < listeningSince) return
    val counted = if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
      if (event.values[0] != 1f || (lastDetectorStep >= 0 && now - lastDetectorStep < 250)) false
      else { lastDetectorStep = now; true }
    } else counter.update(event.values[0], event.values[1], event.values[2], now) == 1
    if (!counted) return
    steps++
    host.onMissionActivity()
    remaining.text = (mission.count - steps).coerceAtLeast(0).toString()
    bar.progress = steps.coerceAtMost(mission.count)
    if (steps >= mission.count) {
      done = true
      stop()
      host.onMissionComplete()
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  companion object {
    fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
      context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    fun hasDetector(context: Context): Boolean = context.getSystemService(SensorManager::class.java)
      ?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
    fun isSupported(context: Context): Boolean = hasDetector(context) || ShakeMission.isSupported(context)
  }
}
