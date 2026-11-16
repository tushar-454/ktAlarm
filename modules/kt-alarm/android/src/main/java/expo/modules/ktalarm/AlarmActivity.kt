package expo.modules.ktalarm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Native full-screen ringing UI. Written without React Native on purpose: it has to appear
 * instantly over the lock screen even when the JS app is not running.
 *
 * Two modes:
 *  - ringing: time, label, Snooze, and Dismiss (or "Start mission" for mission alarms)
 *  - mission: the mission itself; the sound is ducked while the user works on it and comes
 *    back at full volume (and the mission resets) after [MissionRunner.idleTimeoutMs] without effort
 *
 * Also runs missions in preview mode from the alarm editor: no sound, can be closed.
 */
class AlarmActivity : Activity(), MissionRunner.Host {

  companion object {
    private const val TAG = "KtAlarm"
    private const val EXTRA_PREVIEW_MISSION = "expo.modules.ktalarm.PREVIEW_MISSION"
    private const val RELAUNCH_DELAY_MS = 300L
    private val VOLUME_KEYS = setOf(
      KeyEvent.KEYCODE_VOLUME_UP,
      KeyEvent.KEYCODE_VOLUME_DOWN,
      KeyEvent.KEYCODE_VOLUME_MUTE,
    )

    fun intent(context: Context, alarmId: String): Intent =
      Intent(context, AlarmActivity::class.java)
        .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        .addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NO_USER_ACTION or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )

    fun previewIntent(context: Context, mission: Mission): Intent =
      Intent(context, AlarmActivity::class.java)
        .putExtra(EXTRA_PREVIEW_MISSION, mission.toJson().toString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  private val handler = Handler(Looper.getMainLooper())

  private var alarmId: String? = null
  private var alarm: Alarm? = null
  /** Set while previewing a mission from the editor (no alarm ringing). */
  private var previewMission: Mission? = null
  private val isPreview: Boolean get() = previewMission != null
  private var runner: MissionRunner? = null
  /** The camera failed during this ring: go straight to the fallback mission next time. */
  private var cameraFailed = false
  private var walkFailed = false
  private var missionIndex = 0
  private val currentMission: Mission? get() = alarm?.missions?.getOrNull(missionIndex)
  private lateinit var backgroundView: ImageView
  private lateinit var backgroundShade: View
  private var loadedBackground: String? = null

  private lateinit var ringingView: View
  private lateinit var labelView: TextView
  private lateinit var missionHintView: TextView
  private lateinit var snoozeButton: Button
  private lateinit var primaryButton: Button

  private lateinit var missionView: View
  private lateinit var missionTitleView: TextView
  private lateinit var missionNoteView: TextView
  private lateinit var closeButton: TextView
  private lateinit var missionBody: FrameLayout

  private val listener = AlarmEvents.Listener { type, ringingId ->
    if (type != AlarmEvents.Type.RINGING_CHANGED) return@Listener
    runOnUiThread {
      when {
        ringingId != null -> bind(ringingId)
        !isPreview -> finishAndRemoveTask()
      }
    }
  }

  /** No effort for the mission's idle timeout: back to the loud ringing screen, mission starts over. */
  private val idle = Runnable {
    val preview = previewMission
    if (preview != null) {
      Toast.makeText(this, "No activity — mission restarted", Toast.LENGTH_SHORT).show()
      startMission(preview)
    } else {
      AlarmService.setDucked(this, false)
      showRinging()
    }
  }

  /** Home / Recents can't be used to escape a mission alarm. */
  private val relaunch = Runnable {
    val id = alarmId ?: return@Runnable
    if (AlarmService.activeAlarmId != id) return@Runnable
    try {
      startActivity(intent(this, id))
    } catch (e: Exception) {
      Log.w(TAG, "Cannot bring the mission screen back", e)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    showOverLockScreen()
    @Suppress("DEPRECATION")
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    setContentView(buildLayout())
    AlarmEvents.add(listener)
    handle(intent)
    if (!isPreview && savedInstanceState != null && savedInstanceState.getString("missionAlarmId") == alarmId) {
      missionIndex = savedInstanceState.getInt("missionIndex", 0)
        .coerceIn(0, ((alarm?.missions?.size ?: 1) - 1).coerceAtLeast(0))
      refreshRinging()
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString("missionAlarmId", alarmId)
    outState.putInt("missionIndex", missionIndex)
    super.onSaveInstanceState(outState)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handle(intent)
  }

  override fun onStart() {
    super.onStart()
    handler.removeCallbacks(relaunch)
    runner?.onHostShown()
  }

  override fun onStop() {
    super.onStop()
    runner?.onHostHidden()
    val screenOn = getSystemService(PowerManager::class.java).isInteractive
    if (!isFinishing && !isPreview && screenOn && alarm?.hasMission == true && AlarmService.activeAlarmId == alarmId) {
      handler.postDelayed(relaunch, RELAUNCH_DELAY_MS)
    }
  }

  override fun onDestroy() {
    AlarmEvents.remove(listener)
    handler.removeCallbacksAndMessages(null)
    if (runner != null && !isPreview) AlarmService.setDucked(this, false)
    runner?.stop()
    runner = null
    super.onDestroy()
  }

  /** Back must not silently dismiss an alarm. It only closes a preview. */
  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (isPreview) finish()
  }

  /**
   * Volume keys snooze a plain alarm, like most stock clock apps. On a mission alarm they do
   * nothing at all (the service also holds the alarm-stream volume up).
   */
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode !in VOLUME_KEYS || isPreview) return super.onKeyDown(keyCode, event)
    val a = alarm
    if (a != null && !a.hasMission && a.canSnooze && event?.repeatCount == 0) snooze()
    return true
  }

  override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode in VOLUME_KEYS && !isPreview) return true
    return super.onKeyUp(keyCode, event)
  }

  override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
    if (runner != null) touch()
    return super.dispatchTouchEvent(ev)
  }

  // region State

  private fun handle(intent: Intent) {
    val explicitId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
    val preview = intent.getStringExtra(EXTRA_PREVIEW_MISSION)?.let {
      runCatching { Mission.fromJson(JSONObject(it)) }.getOrNull()
    }
    if (explicitId == null && preview != null) {
      // A real alarm on screen wins over a preview.
      if (AlarmService.activeAlarmId != null && alarmId != null) return
      alarmId = null
      alarm = null
      previewMission = preview
      loadBackground(null)
      startMission(preview)
      return
    }
    bind(explicitId ?: AlarmService.activeAlarmId)
  }

  private fun bind(id: String?) {
    // Opened from a stale notification after the alarm already stopped.
    if (id == null || AlarmService.activeAlarmId == null) {
      finishAndRemoveTask()
      return
    }
    val keepMission = id == alarmId && runner != null && !isPreview
    if (id != alarmId) {
      cameraFailed = false
      walkFailed = false
      missionIndex = 0
    }
    previewMission = null
    alarmId = id
    alarm = AlarmStore.get(this, id)
    loadBackground(alarm?.backgroundUri)
    if (keepMission) refreshRinging() else showRinging()
  }

  private fun showRinging() {
    stopMission()
    missionView.visibility = View.GONE
    ringingView.visibility = View.VISIBLE
    refreshRinging()
  }

  private fun refreshRinging() {
    val a = alarm
    labelView.text = a?.label?.takeIf { it.isNotBlank() } ?: "Alarm"
    val mission = currentMission
    missionHintView.text = mission?.let {
      if ((a?.missions?.size ?: 0) > 1) "${missionIndex + 1}/${a?.missions?.size} · ${it.describe()}" else it.describe()
    }.orEmpty()
    missionHintView.visibility = if (mission != null) View.VISIBLE else View.GONE
    primaryButton.text = if (mission != null) {
      if (missionIndex > 0) "Continue missions" else "Start mission${if ((a?.missions?.size ?: 0) > 1) "s" else ""}"
    } else "Dismiss"

    snoozeButton.visibility = if (a == null || a.canSnooze) View.VISIBLE else View.GONE
    val left = a?.snoozesLeft
    snoozeButton.text = "Snooze ${a?.snoozeMinutes ?: 5} min" + if (left != null) " · $left left" else ""
  }

  private fun startMission(mission: Mission) {
    stopMission()
    val r = MissionRunner.create(this, mission, this)
    runner = r
    missionTitleView.text = r.title.uppercase() +
      if (!isPreview && (alarm?.missions?.size ?: 0) > 1) " · ${missionIndex + 1}/${alarm?.missions?.size}" else ""
    missionNoteView.text = if (isPreview) {
      "Preview — no alarm is ringing"
    } else {
      "Stop for ${r.idleTimeoutMs / 1000} seconds and the alarm gets loud again"
    }
    closeButton.visibility = if (isPreview) View.VISIBLE else View.GONE
    missionBody.addView(r.view, FrameLayout.LayoutParams(-1, -1))
    ringingView.visibility = View.GONE
    missionView.visibility = View.VISIBLE
    if (!isPreview) AlarmService.setDucked(this, true)
    r.start()
    touch()
  }

  private fun stopMission() {
    handler.removeCallbacks(idle)
    runner?.stop()
    runner = null
    if (::missionBody.isInitialized) missionBody.removeAllViews()
  }

  private fun touch() {
    handler.removeCallbacks(idle)
    runner?.let { handler.postDelayed(idle, it.idleTimeoutMs) }
  }

  override fun onMissionActivity() = touch()

  /** A camera mission lost its camera: never leave the user with a mission they can't finish. */
  override fun onMissionUnavailable() {
    handler.post {
      if (runner == null) return@post
      val walking = (previewMission ?: currentMission)?.type == MissionType.WALK
      if (walking) walkFailed = true else cameraFailed = true
      Toast.makeText(this, "${if (walking) "Motion sensor" else "Camera"} unavailable — solve these instead", Toast.LENGTH_LONG).show()
      startMission(MissionRunner.FALLBACK)
    }
  }

  override fun onMissionComplete() {
    handler.removeCallbacks(idle)
    if (isPreview) {
      Toast.makeText(this, "Mission complete", Toast.LENGTH_SHORT).show()
      handler.postDelayed({ finish() }, 400)
      return
    }
    val id = alarmId ?: return
    if (runner == null) return
    stopMission()
    if (missionIndex + 1 < (alarm?.missions?.size ?: 0)) {
      missionIndex++
      startCurrentMission()
      return
    }
    AlarmController.completeMission(this, id)
    finishAndRemoveTask()
  }

  private fun onPrimary() {
    val mission = currentMission
    if (mission != null) {
      startCurrentMission()
      return
    }
    val id = alarmId ?: return
    if (AlarmController.dismiss(this, id)) {
      // Leaves the user on the lock screen — no forced unlock just to dismiss.
      finishAndRemoveTask()
    }
  }

  private fun startCurrentMission() {
    val mission = currentMission ?: return
    val usesCamera = mission.type == MissionType.SQUAT || mission.type == MissionType.BARCODE
    val unavailable = (cameraFailed && usesCamera) || (walkFailed && mission.type == MissionType.WALK)
    startMission(if (unavailable) MissionRunner.FALLBACK else mission)
  }

  private fun loadBackground(uri: String?) {
    if (loadedBackground == uri) return
    loadedBackground = uri
    backgroundView.setImageDrawable(null)
    backgroundShade.visibility = View.GONE
    if (uri == null) return
    PresetMedia.background(uri)?.let {
      backgroundView.setImageDrawable(it)
      backgroundShade.visibility = View.VISIBLE
      return
    }
    AlarmMediaStore.io.execute {
      // Imports are already resized to at most 1600px; decoding never blocks the ringing UI.
      val bitmap = runCatching { BitmapFactory.decodeFile(android.net.Uri.parse(uri).path) }.getOrNull()
      runOnUiThread {
        if (isDestroyed || isFinishing || loadedBackground != uri) {
          bitmap?.recycle()
        } else if (bitmap != null) {
          backgroundView.setImageBitmap(bitmap)
          backgroundShade.visibility = View.VISIBLE
        }
      }
    }
  }

  private fun snooze() {
    val id = alarmId ?: return
    if (AlarmController.snooze(this, id)) finishAndRemoveTask() else refreshRinging()
  }

  // endregion

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

  private fun dp(v: Int): Int = Ui.dp(this, v)

  /** Pads [view] by [base] plus system bars and keyboard (the activity is edge-to-edge on new Android). */
  private fun padForInsets(view: View, left: Int, top: Int, right: Int, bottom: Int) {
    view.setOnApplyWindowInsetsListener { v, insets ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        v.setPadding(left + i.left, top + i.top, right + i.right, bottom + i.bottom)
      } else {
        @Suppress("DEPRECATION")
        v.setPadding(
          left + insets.systemWindowInsetLeft,
          top + insets.systemWindowInsetTop,
          right + insets.systemWindowInsetRight,
          bottom + insets.systemWindowInsetBottom,
        )
      }
      insets
    }
  }

  private fun buildLayout(): View {
    val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
    backgroundView = ImageView(this).apply {
      scaleType = ImageView.ScaleType.CENTER_CROP
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    backgroundShade = View(this).apply {
      setBackgroundColor(Color.argb(175, 0, 0, 0))
      visibility = View.GONE
    }
    root.addView(backgroundView, FrameLayout.LayoutParams(-1, -1))
    root.addView(backgroundShade, FrameLayout.LayoutParams(-1, -1))
    ringingView = buildRingingView()
    missionView = buildMissionView().apply { visibility = View.GONE }
    root.addView(ringingView, FrameLayout.LayoutParams(-1, -1))
    root.addView(missionView, FrameLayout.LayoutParams(-1, -1))
    return root
  }

  private fun buildRingingView(): View {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    padForInsets(root, dp(28), dp(48), dp(28), dp(40))

    root.addView(Ui.text(this, 14f, Ui.ACCENT, bold = true, value = "ALARM").apply { letterSpacing = 0.3f })

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
      setTextColor(Ui.MUTED)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
      gravity = Gravity.CENTER
    })

    labelView = Ui.text(this, 24f).apply { setPadding(0, dp(24), 0, 0) }
    root.addView(labelView)

    missionHintView = Ui.text(this, 16f, Ui.ACCENT).apply {
      setPadding(dp(16), dp(8), dp(16), dp(8))
      background = Ui.rounded(this@AlarmActivity, Ui.SURFACE)
    }
    root.addView(missionHintView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })

    root.addView(View(this), LinearLayout.LayoutParams(0, 0, 1.4f))

    snoozeButton = Ui.secondaryButton(this, "Snooze") { snooze() }
    root.addView(snoozeButton, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(16) })

    primaryButton = Ui.primaryButton(this, "Dismiss") { onPrimary() }
    root.addView(primaryButton, LinearLayout.LayoutParams(-1, dp(64)))
    return root
  }

  private fun buildMissionView(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    padForInsets(root, dp(24), dp(24), dp(24), dp(24))

    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    missionTitleView = Ui.text(this, 14f, Ui.ACCENT, bold = true).apply {
      letterSpacing = 0.3f
      gravity = Gravity.START
    }
    header.addView(missionTitleView, LinearLayout.LayoutParams(0, -2, 1f))
    closeButton = Ui.text(this, 16f, Ui.MUTED, value = "Close").apply {
      setPadding(dp(12), dp(8), dp(12), dp(8))
      setOnClickListener { finish() }
    }
    header.addView(closeButton)
    root.addView(header, LinearLayout.LayoutParams(-1, dp(44)))

    missionNoteView = Ui.text(this, 13f, Ui.MUTED).apply { gravity = Gravity.START }
    root.addView(missionNoteView)

    missionBody = FrameLayout(this)
    root.addView(missionBody, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(24) })
    return root
  }

  // endregion
}
