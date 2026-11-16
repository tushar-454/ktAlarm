package expo.modules.ktalarm

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlin.math.sqrt

internal class WalkMission(context: Context, private val mission: Mission, host: Host) :
  MissionRunner(context, host), SensorEventListener {
  override val title = "Walk"
  override val idleTimeoutMs = 60_000L
  private val sensors = context.getSystemService(SensorManager::class.java)
  private val counter = WalkCounter()
  private var listening = false
  private var done = false
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
      value = "Keep a steady pace, with your phone in your hand or pocket.\nStep estimates begin after a few strides."),
      LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
  }

  override fun start() {
    if (done || listening) return
    counter.resetMotion()
    val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    listening = sensor != null && sensors.registerListener(this, sensor, 20_000)
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
    val x = event.values[0]
    val y = event.values[1]
    val z = event.values[2]
    if (counter.update(sqrt(x * x + y * y + z * z), event.timestamp / 1_000_000) == 0) return
    host.onMissionActivity()
    remaining.text = (mission.count - counter.steps).coerceAtLeast(0).toString()
    bar.progress = counter.steps.coerceAtMost(mission.count)
    if (counter.steps >= mission.count) {
      done = true
      stop()
      host.onMissionComplete()
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
