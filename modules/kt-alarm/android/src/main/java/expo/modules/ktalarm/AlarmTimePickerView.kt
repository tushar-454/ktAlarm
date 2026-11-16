package expo.modules.ktalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.Scroller
import android.widget.Button
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Three visible numbers per column. Fling, drawing and wrapping stay on the UI thread. */
class AlarmTimePickerView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  override val shouldUseAndroidLayout = true
  val onTimeChanged by EventDispatcher()
  private var pm = false
  private var hourInitialized = false
  private val hours: Wheel = Wheel(context, 12, "Hours", true) { emitTime() }
  private val minutes: Wheel = Wheel(context, 60, "Minutes", false) { emitTime() }
  private val colon = Ui.text(context, 42f, value = ":")
  private val amButton = periodButton("AM", false)
  private val pmButton = periodButton("PM", true)

  private fun periodButton(label: String, selectsPm: Boolean) = Button(context).apply {
    text = label
    textSize = 16f
    setPadding(0, 0, 0, 0)
    minimumWidth = 0
    minimumHeight = 0
    stateListAnimator = null
    setOnClickListener {
      if (pm != selectsPm) {
        pm = selectsPm
        updatePeriod()
        emitTime()
      }
    }
  }

  init {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    addView(hours, LayoutParams(0, -1, 1f))
    addView(colon, LayoutParams(Ui.dp(context, 30), -1))
    addView(minutes, LayoutParams(0, -1, 1f))
    val periods = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      addView(amButton, LinearLayout.LayoutParams(-1, Ui.dp(context, 48)))
      addView(pmButton, LinearLayout.LayoutParams(-1, Ui.dp(context, 48)).apply {
        topMargin = Ui.dp(context, 8)
      })
    }
    addView(periods, LayoutParams(Ui.dp(context, 60), -2).apply {
      marginStart = Ui.dp(context, 8)
    })
    updatePeriod()
  }

  fun setInitialHour(value: Int) {
    if (hourInitialized) return
    hourInitialized = true
    hours.initialize(value % 12)
    pm = value >= 12
    updatePeriod()
  }
  fun setInitialMinute(value: Int) = minutes.initialize(value)
  fun setDark(value: Boolean) {
    hours.dark = value
    minutes.dark = value
    colon.setTextColor(if (value) Color.WHITE else Ui.BG)
  }
  private fun emitTime() {
    onTimeChanged(mapOf("hour" to hours.value + if (pm) 12 else 0, "minute" to minutes.value))
  }
  private fun updatePeriod() {
    for ((button, selected) in listOf(amButton to !pm, pmButton to pm)) {
      button.isSelected = selected
      button.setTextColor(if (selected) Ui.BG else Ui.ACCENT)
      button.background = Ui.rounded(context, if (selected) Ui.ACCENT else Color.TRANSPARENT, Ui.ACCENT, 16)
    }
  }
}

private class Wheel(context: Context, private val size: Int, private val label: String, private val hours: Boolean,
  private val changed: () -> Unit) : View(context) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
  private val labels = Array(size) { (if (hours && it == 0) 12 else it).toString().padStart(2, '0') }
  private val scroller = Scroller(context)
  private val config = ViewConfiguration.get(context)
  private var velocity: VelocityTracker? = null
  private var lastY = 0f
  private var lastScrollY = 0
  private var position = 0f
  private var initialized = false
  private var dragging = false
  private val rowHeight: Float get() = height.coerceAtLeast(3) / 3f
  var value = 0
    private set
  var dark = false
    set(value) { field = value; invalidate() }

  init {
    isFocusable = true
    isClickable = true
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    contentDescription = "$label, 00"
  }

  fun initialize(value: Int) {
    if (initialized) return
    initialized = true
    position = value.coerceIn(0, size - 1).toFloat()
    this.value = position.toInt()
    contentDescription = "$label, ${labels[this.value]}"
    invalidate()
  }

  private fun update() {
    val next = Math.floorMod(position.roundToInt(), size)
    if (next != value) {
      value = next
      contentDescription = "$label, ${labels[value]}"
      changed()
    }
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val centerX = width / 2f
    val centerY = height / 2f
    paint.color = if (dark) Color.rgb(26, 31, 39) else Color.rgb(245, 245, 245)
    paint.alpha = 255
    canvas.drawRoundRect(0f, centerY - rowHeight / 2, width.toFloat(), centerY + rowHeight / 2,
      Ui.dp(context, 18).toFloat(), Ui.dp(context, 18).toFloat(), paint)
    paint.textSize = minOf(52 * resources.displayMetrics.scaledDensity, rowHeight * 0.85f)
    val baseline = -(paint.ascent() + paint.descent()) / 2
    val nearest = position.roundToInt()
    for (index in nearest - 2..nearest + 2) {
      val offset = index - position
      if (abs(offset) > 1.5f) continue
      val angle = offset * PI / 3
      val scale = cos(angle).toFloat().coerceAtLeast(0f)
      canvas.save()
      canvas.translate(centerX, centerY + sin(angle).toFloat() * rowHeight / 0.8660254f)
      canvas.scale(0.8f + 0.2f * scale, scale)
      paint.color = if (dark) Color.WHITE else Ui.BG
      paint.alpha = (70 + scale * 185).toInt().coerceIn(0, 255)
      canvas.drawText(labels[Math.floorMod(index, size)], 0f, baseline, paint)
      canvas.restore()
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        parent.requestDisallowInterceptTouchEvent(true)
        scroller.forceFinished(true)
        dragging = true
        velocity?.recycle()
        velocity = VelocityTracker.obtain().apply { addMovement(event) }
        lastY = event.y
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        velocity?.addMovement(event)
        position += (lastY - event.y) / rowHeight
        lastY = event.y
        update()
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        velocity?.addMovement(event)
        velocity?.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
        val speed = if (event.actionMasked == MotionEvent.ACTION_UP) -(velocity?.yVelocity ?: 0f) else 0f
        dragging = false
        velocity?.recycle()
        velocity = null
        lastScrollY = 0
        if (abs(speed) >= config.scaledMinimumFlingVelocity) {
          scroller.fling(0, 0, 0, speed.roundToInt(), 0, 0, Int.MIN_VALUE / 4, Int.MAX_VALUE / 4)
          postInvalidateOnAnimation()
        } else {
          if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
          snap()
        }
        parent.requestDisallowInterceptTouchEvent(false)
      }
    }
    return true
  }

  override fun performClick(): Boolean { super.performClick(); return true }

  override fun computeScroll() {
    if (scroller.computeScrollOffset()) {
      position += (scroller.currY - lastScrollY) / rowHeight
      lastScrollY = scroller.currY
      update()
      postInvalidateOnAnimation()
    } else if (!dragging) {
      snap()
    }
  }

  private fun snap() {
    val delta = ((position.roundToInt() - position) * rowHeight).roundToInt()
    if (abs(delta) > 1) {
      lastScrollY = 0
      scroller.startScroll(0, 0, 0, delta, 140)
      postInvalidateOnAnimation()
    } else {
      // Rebase at rest: unlimited cycles without floating-point precision loss.
      val normalized = Math.floorMod(position.roundToInt(), size).toFloat()
      if (position != normalized) {
        position = normalized
        update()
      }
    }
  }

  override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
    super.onInitializeAccessibilityNodeInfo(info)
    info.className = "android.widget.NumberPicker"
    info.isScrollable = true
    info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
      AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, if (hours) 1f else 0f,
      if (hours) 12f else 59f, labels[value].toFloat())
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
  }

  override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
    val delta = when (action) {
      AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
      AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
      else -> return super.performAccessibilityAction(action, arguments)
    }
    scroller.forceFinished(true)
    position = (value + delta).toFloat()
    update()
    announceForAccessibility(contentDescription)
    return true
  }

  override fun onDetachedFromWindow() {
    scroller.forceFinished(true)
    velocity?.recycle()
    velocity = null
    super.onDetachedFromWindow()
  }
}
