package expo.modules.ktalarm

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sqrt

/**
 * One running mission on the alarm screen. Implementations build their own view and report
 * back through [Host]: any user effort resets the idle timer, completion turns the alarm off.
 */
internal abstract class MissionRunner(protected val context: Context, protected val host: Host) {
  interface Host {
    /** The user is actively working on the mission. */
    fun onMissionActivity()
    fun onMissionComplete()
  }

  abstract val title: String
  abstract val view: View
  open fun start() {}
  open fun stop() {}

  protected fun dp(v: Int) = Ui.dp(context, v)

  companion object {
    fun create(context: Context, mission: Mission, host: Host): MissionRunner = when (mission.type) {
      MissionType.MATH -> MathMission(context, mission, host)
      MissionType.TYPING -> TypingMission(context, mission, host)
      MissionType.SHAKE ->
        if (ShakeMission.isSupported(context)) {
          ShakeMission(context, mission, host)
        } else {
          // No accelerometer: never leave the user with an impossible mission.
          MathMission(context, Mission(MissionType.MATH, difficulty = 1, count = 3), host)
        }
    }
  }
}

// region Math

internal data class MathProblem(val text: String, val answer: Int)

internal object MathProblems {
  fun generate(difficulty: Int): MathProblem = when (difficulty) {
    0 -> {
      val a = (1..9).random()
      val b = (1..9).random()
      MathProblem("$a + $b", a + b)
    }
    1 -> {
      val a = (10..99).random()
      val b = (10..99).random()
      if ((0..1).random() == 0) {
        MathProblem("$a + $b", a + b)
      } else {
        MathProblem("${maxOf(a, b)} − ${minOf(a, b)}", maxOf(a, b) - minOf(a, b))
      }
    }
    2 -> {
      val a = (11..19).random()
      val b = (3..9).random()
      val c = (10..99).random()
      MathProblem("$a × $b + $c", a * b + c)
    }
    else -> {
      val a = (12..59).random()
      val b = (12..39).random()
      MathProblem("$a × $b", a * b)
    }
  }
}

internal class MathMission(context: Context, private val mission: Mission, host: Host) : MissionRunner(context, host) {
  override val title = "Math"

  private val handler = Handler(Looper.getMainLooper())
  private var solved = 0
  private var problem = MathProblems.generate(mission.difficulty)
  private var input = ""
  private var done = false

  private val progressView = Ui.text(context, 16f, Ui.MUTED)
  private val problemView = Ui.text(context, 40f, Color.WHITE, bold = true)
  private val answerView = Ui.text(context, 44f, Ui.ACCENT, bold = true).apply {
    background = Ui.rounded(context, Ui.SURFACE, radiusDp = 20)
    setPadding(dp(24), dp(8), dp(24), dp(8))
    minWidth = dp(180)
  }
  private val feedbackView = Ui.text(context, 15f, Ui.ERROR)

  override val view: View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    addView(progressView)
    addView(problemView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    addView(answerView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
    addView(feedbackView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
    addView(keypad(), LinearLayout.LayoutParams(-1, -2))
  }

  init {
    render()
  }

  private fun keypad(): View {
    val rows = listOf(
      listOf("1", "2", "3"),
      listOf("4", "5", "6"),
      listOf("7", "8", "9"),
      listOf("⌫", "0", "✓"),
    )
    return LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      rows.forEach { keys ->
        addView(
          LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEach { key -> addView(key(key), LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }) }
          },
        )
      }
    }
  }

  private fun key(label: String) = Button(context).apply {
    text = label
    isAllCaps = false
    textSize = 26f
    stateListAnimator = null
    val submit = label == "✓"
    setTextColor(if (submit) Ui.BG else Color.WHITE)
    background = Ui.rounded(context, if (submit) Ui.ACCENT else Ui.SURFACE, radiusDp = 20)
    setOnClickListener {
      host.onMissionActivity()
      if (submit) {
        submit()
        return@setOnClickListener
      }
      if (label == "⌫") input = input.dropLast(1) else if (input.length < 6) input += label
      feedbackView.text = ""
      render()
    }
  }

  private fun submit() {
    if (done || input.isEmpty()) return
    if (input.toIntOrNull() == problem.answer) {
      solved++
      input = ""
      feedbackView.text = ""
      if (solved >= mission.count) {
        done = true
        host.onMissionComplete()
        return
      }
      problem = MathProblems.generate(mission.difficulty)
      render()
    } else {
      feedbackView.text = "Not quite — try again"
      answerView.setTextColor(Ui.ERROR)
      view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
      handler.postDelayed({
        input = ""
        render()
      }, 500)
    }
  }

  private fun render() {
    progressView.text = "Problem ${solved + 1} of ${mission.count}"
    problemView.text = "${problem.text} = ?"
    answerView.text = input.ifEmpty { " " }
    answerView.setTextColor(Ui.ACCENT)
  }

  override fun stop() {
    handler.removeCallbacksAndMessages(null)
  }
}

// endregion

// region Shake

internal class ShakeMission(context: Context, private val mission: Mission, host: Host) :
  MissionRunner(context, host), SensorEventListener {

  companion object {
    /** Total acceleration, in g, that counts as one shake. Resting is ~1 g. */
    private const val THRESHOLD_G = 2.2f
    private const val MIN_GAP_MS = 250L

    fun isSupported(context: Context): Boolean =
      context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
  }

  override val title = "Shake"

  private val sensors = context.getSystemService(SensorManager::class.java)
  private var shakes = 0
  private var lastShakeAt = 0L
  private var done = false

  private val remainingView = Ui.text(context, 96f, Color.WHITE, bold = true)
  private val bar = Ui.progressBar(context, mission.count)

  override val view: View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    addView(Ui.text(context, 18f, Ui.MUTED, value = "Shake your phone hard"))
    addView(remainingView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    addView(Ui.text(context, 16f, Ui.MUTED, value = "shakes left"))
    addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(32) })
  }

  init {
    render()
  }

  override fun start() {
    sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
      sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
    }
  }

  override fun stop() {
    sensors.unregisterListener(this)
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (done) return
    val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
    val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
    val now = System.currentTimeMillis()
    if (g < THRESHOLD_G || now - lastShakeAt < MIN_GAP_MS) return
    lastShakeAt = now
    shakes++
    host.onMissionActivity()
    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    render()
    if (shakes >= mission.count) {
      done = true
      stop()
      host.onMissionComplete()
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  private fun render() {
    remainingView.text = (mission.count - shakes).coerceAtLeast(0).toString()
    bar.progress = shakes
  }
}

// endregion

// region Typing

internal class TypingMission(context: Context, private val mission: Mission, host: Host) : MissionRunner(context, host) {

  companion object {
    private val PHRASES = listOf(
      "I am awake and ready for today",
      "Today is going to be a great day",
      "I get out of bed the first time",
      "Small steps every morning add up",
      "My future self will thank me",
      "I choose energy over excuses",
      "The early hours belong to me",
      "I keep the promises I make to myself",
      "Discipline is choosing what I want most",
      "Rise up and make it happen",
      "A fresh start begins right now",
      "I am stronger than my snooze button",
    )

    /** Case and extra spaces don't matter; the words do. */
    fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()
  }

  override val title = "Typing"

  private val phrases = PHRASES.shuffled().take(mission.count)
  private var index = 0
  private var done = false

  private val progressView = Ui.text(context, 16f, Ui.MUTED)
  private val phraseView = Ui.text(context, 26f, Color.WHITE, bold = true).apply {
    setTextIsSelectable(false)
    setPadding(0, dp(16), 0, dp(16))
  }
  private val input = EditText(context).apply {
    // No suggestions / autocorrect / learning: the user has to type every letter.
    inputType = InputType.TYPE_CLASS_TEXT or
      InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
      InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
      (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
    setTextColor(Color.WHITE)
    setHintTextColor(Ui.BORDER)
    hint = "Type the phrase above"
    textSize = 20f
    background = Ui.rounded(context, Ui.SURFACE, radiusDp = 16)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    isLongClickable = false
    blockPaste(this)
  }

  override val view: View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    addView(progressView)
    addView(phraseView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
  }

  init {
    input.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
      override fun afterTextChanged(s: Editable?) = onTyped(s?.toString().orEmpty())
    })
    render()
  }

  private fun onTyped(typed: String) {
    if (done) return
    host.onMissionActivity()
    val target = normalize(phrases[index])
    val current = normalize(typed)
    input.setTextColor(if (target.startsWith(current)) Color.WHITE else Ui.ERROR)
    if (current != target) return
    index++
    if (index >= phrases.size) {
      done = true
      hideKeyboard()
      host.onMissionComplete()
      return
    }
    input.text.clear()
    render()
  }

  private fun render() {
    progressView.text = "Phrase ${index + 1} of ${phrases.size}"
    phraseView.text = phrases[index]
  }

  override fun start() {
    input.requestFocus()
    input.postDelayed({
      context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }, 200)
  }

  override fun stop() = hideKeyboard()

  private fun hideKeyboard() {
    context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
  }

  private fun blockPaste(field: TextView) {
    val deny = object : ActionMode.Callback {
      override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
      override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
      override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
      override fun onDestroyActionMode(mode: ActionMode?) = Unit
    }
    field.customSelectionActionModeCallback = deny
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) field.customInsertionActionModeCallback = deny
  }
}

// endregion
