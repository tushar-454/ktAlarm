package expo.modules.ktalarm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/** Tiny view toolkit for the native alarm screens (no XML resources, no AppCompat). */
internal object Ui {
  val BG: Int = Color.parseColor("#0E1116")
  val SURFACE: Int = Color.parseColor("#1A1F27")
  val ACCENT: Int = Color.parseColor("#FF8A3D")
  val MUTED: Int = Color.parseColor("#9AA3AF")
  val BORDER: Int = Color.parseColor("#4B5563")
  val ERROR: Int = Color.parseColor("#F87171")
  val SUCCESS: Int = Color.parseColor("#34D399")

  fun dp(context: Context, v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

  fun rounded(context: Context, fill: Int, stroke: Int? = null, radiusDp: Int = 40) = GradientDrawable().apply {
    cornerRadius = dp(context, radiusDp).toFloat()
    setColor(fill)
    if (stroke != null) setStroke(dp(context, 2), stroke)
  }

  fun text(
    context: Context,
    sizeSp: Float,
    color: Int = Color.WHITE,
    bold: Boolean = false,
    value: CharSequence = "",
  ) = TextView(context).apply {
    text = value
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    gravity = Gravity.CENTER
    if (bold) typeface = Typeface.DEFAULT_BOLD
  }

  fun primaryButton(context: Context, label: String, onClick: () -> Unit) = Button(context).apply {
    text = label
    isAllCaps = false
    setTextColor(BG)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    typeface = Typeface.DEFAULT_BOLD
    background = rounded(context, ACCENT)
    stateListAnimator = null
    setOnClickListener { onClick() }
  }

  fun secondaryButton(context: Context, label: String, onClick: () -> Unit) = Button(context).apply {
    text = label
    isAllCaps = false
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    background = rounded(context, Color.TRANSPARENT, BORDER)
    stateListAnimator = null
    setOnClickListener { onClick() }
  }

  fun progressBar(context: Context, max: Int) =
    ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
      this.max = max
      isIndeterminate = false
      progressTintList = ColorStateList.valueOf(ACCENT)
      progressBackgroundTintList = ColorStateList.valueOf(SURFACE)
    }
}
