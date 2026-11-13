package expo.modules.ktalarm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * Native full-screen ringing UI. Written without React Native on purpose: it has to appear
 * instantly over the lock screen even when the JS app is not running.
 */
class AlarmActivity : Activity() {

  companion object {
    fun intent(context: Context, alarmId: String): Intent =
      Intent(context, AlarmActivity::class.java)
        .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        .addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NO_USER_ACTION or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
  }

  private var alarmId: String? = null
  private lateinit var labelView: TextView
  private lateinit var snoozeButton: Button

  private val listener = AlarmEvents.Listener { type, ringingId ->
    if (type != AlarmEvents.Type.RINGING_CHANGED) return@Listener
    runOnUiThread {
      if (ringingId == null) finishAndRemoveTask() else bind(ringingId)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    showOverLockScreen()
    setContentView(buildLayout())
    AlarmEvents.add(listener)
    bind(intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: AlarmService.activeAlarmId)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    bind(intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: AlarmService.activeAlarmId)
  }

  override fun onDestroy() {
    AlarmEvents.remove(listener)
    super.onDestroy()
  }

  /** Back must not silently dismiss an alarm. */
  @Deprecated("Deprecated in Java")
  override fun onBackPressed() = Unit

  /** Volume keys snooze, like most stock clock apps. */
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
      alarmId?.let { AlarmController.snooze(this, it) }
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  private fun bind(id: String?) {
    // Opened from a stale notification after the alarm already stopped.
    if (id == null || AlarmService.activeAlarmId == null) {
      finishAndRemoveTask()
      return
    }
    alarmId = id
    val alarm = AlarmStore.get(this, id)
    labelView.text = alarm?.label?.takeIf { it.isNotBlank() } ?: "Alarm"
    snoozeButton.text = "Snooze ${alarm?.snoozeMinutes ?: 5} min"
  }

  private fun showOverLockScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
      )
    }
    window.addFlags(
      WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
    )
  }

  // region Layout (built in code — no XML resources needed)

  private fun dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

  private fun pill(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
    cornerRadius = dp(40).toFloat()
    setColor(fill)
    if (stroke != null) setStroke(dp(2), stroke)
  }

  private fun buildLayout(): View {
    val accent = Color.parseColor("#FF8A3D")
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setBackgroundColor(Color.parseColor("#0E1116"))
      fitsSystemWindows = true
      setPadding(dp(28), dp(48), dp(28), dp(40))
    }

    root.addView(TextView(this).apply {
      text = "ALARM"
      setTextColor(accent)
      letterSpacing = 0.3f
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
    })

    root.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

    root.addView(TextClock(this).apply {
      format12Hour = "h:mm"
      format24Hour = "HH:mm"
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
      typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
      gravity = Gravity.CENTER
    })

    root.addView(TextClock(this).apply {
      format12Hour = "EEEE, d MMMM"
      format24Hour = "EEEE, d MMMM"
      setTextColor(Color.parseColor("#9AA3AF"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
      gravity = Gravity.CENTER
    })

    labelView = TextView(this).apply {
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
      gravity = Gravity.CENTER
      setPadding(0, dp(24), 0, 0)
    }
    root.addView(labelView)

    root.addView(View(this), LinearLayout.LayoutParams(0, 0, 1.4f))

    snoozeButton = Button(this).apply {
      isAllCaps = false
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
      background = pill(Color.TRANSPARENT, Color.parseColor("#4B5563"))
      stateListAnimator = null
      setOnClickListener { alarmId?.let { AlarmController.snooze(this@AlarmActivity, it) } }
    }
    root.addView(snoozeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))

    root.addView(View(this), LinearLayout.LayoutParams(0, dp(16)))

    root.addView(
      Button(this).apply {
        text = "Dismiss"
        isAllCaps = false
        setTextColor(Color.parseColor("#0E1116"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        background = pill(accent)
        stateListAnimator = null
        setOnClickListener { dismissAlarm() }
      },
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)),
    )
    return root
  }

  // endregion

  private fun dismissAlarm() {
    alarmId?.let { AlarmController.dismiss(this, it) }
    // Leaves the user on the lock screen — no forced unlock just to dismiss.
    finishAndRemoveTask()
  }
}
