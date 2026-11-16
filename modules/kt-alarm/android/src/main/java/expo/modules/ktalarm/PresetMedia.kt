package expo.modules.ktalarm

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri

/** Stable identifiers in stored alarms; resource IDs may change between builds. */
internal object PresetMedia {
  private val tones = mapOf(
    "preset:chime" to R.raw.kt_tone_chime,
    "preset:sunrise" to R.raw.kt_tone_sunrise,
    "preset:bell" to R.raw.kt_tone_bell,
  )
  private val sleep = mapOf(
    "preset:white" to R.raw.kt_sleep_white, "preset:pink" to R.raw.kt_sleep_pink,
    "preset:brown" to R.raw.kt_sleep_brown, "preset:rain" to R.raw.kt_sleep_rain,
    "preset:ocean" to R.raw.kt_sleep_ocean, "preset:fan" to R.raw.kt_sleep_fan,
  )
  private val backgrounds = mapOf(
    "preset:dawn" to intArrayOf(Color.rgb(43, 25, 66), Color.rgb(196, 100, 98), Color.rgb(244, 178, 125)),
    "preset:forest" to intArrayOf(Color.rgb(7, 26, 30), Color.rgb(25, 75, 70), Color.rgb(88, 137, 115)),
    "preset:midnight" to intArrayOf(Color.rgb(8, 15, 43), Color.rgb(34, 42, 92), Color.rgb(78, 66, 123)),
    "preset:sea" to intArrayOf(Color.rgb(8, 31, 57), Color.rgb(27, 88, 113), Color.rgb(97, 164, 168)),
  )

  fun contains(value: String, kind: String): Boolean = when (kind) {
    "background" -> value in backgrounds
    "sleep" -> value in sleep
    else -> value in tones
  }

  fun audioUri(context: Context, value: String): Uri {
    val id = tones[value] ?: sleep[value]
    return if (id != null) Uri.parse("android.resource://${context.packageName}/$id") else Uri.parse(value)
  }

  fun background(value: String): GradientDrawable? = backgrounds[value]?.let {
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, it)
  }
}
