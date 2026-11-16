package expo.modules.ktalarm

import org.json.JSONObject

/** A task the user must complete before a ringing alarm can be dismissed. */
enum class MissionType(
  val key: String,
  val minCount: Int,
  val maxCount: Int,
  val defaultCount: Int,
) {
  MATH("math", 1, 10, 3),
  SHAKE("shake", 10, 100, 30),
  WALK("walk", 10, 300, 30),
  SQUAT("squat", 5, 50, 10),
  TYPING("typing", 1, 5, 2),
  /** Scan one registered code; count is always 1. */
  BARCODE("barcode", 1, 1, 1);

  companion object {
    fun from(key: String?): MissionType? = values().firstOrNull { it.key == key }
  }
}

/**
 * @param difficulty only used by [MissionType.MATH]: 0 = easy … 3 = very hard.
 * @param count problems / shakes / squats / phrases to complete.
 * @param barcodeValue only for [MissionType.BARCODE]: the registered code's raw value.
 * @param barcodeName only for [MissionType.BARCODE]: what the user called it ("Toothpaste").
 */
data class Mission(
  val type: MissionType,
  val difficulty: Int = 1,
  val count: Int = type.defaultCount,
  val barcodeValue: String? = null,
  val barcodeName: String? = null,
) {
  fun normalized(): Mission = copy(
    difficulty = difficulty.coerceIn(0, MAX_DIFFICULTY),
    count = count.coerceIn(type.minCount, type.maxCount),
  )

  /** One line for the ringing screen and notification. */
  fun describe(): String = when (type) {
    MissionType.MATH -> if (count == 1) "Solve a math problem" else "Solve $count math problems"
    MissionType.SHAKE -> "Shake your phone $count times"
    MissionType.WALK -> "Walk $count steps with your phone"
    MissionType.SQUAT -> "Do $count squats"
    MissionType.TYPING -> if (count == 1) "Type a phrase" else "Type $count phrases"
    MissionType.BARCODE -> "Scan ${barcodeName?.takeIf { it.isNotBlank() } ?: "your code"}"
  }

  fun toJson(): JSONObject = JSONObject()
    .put("type", type.key)
    .put("difficulty", difficulty)
    .put("count", count)
    .apply {
      if (barcodeValue != null) put("barcodeValue", barcodeValue)
      if (barcodeName != null) put("barcodeName", barcodeName)
    }

  fun toMap(): Map<String, Any?> = mapOf(
    "type" to type.key,
    "difficulty" to difficulty,
    "count" to count,
    "barcodeValue" to barcodeValue,
    "barcodeName" to barcodeName,
  )

  companion object {
    const val MAX_DIFFICULTY = 3

    /**
     * Unknown types (e.g. written by a newer app version) are dropped, not crashed on.
     * A barcode mission without a registered code is invalid.
     */
    fun create(
      typeKey: String?,
      difficulty: Int,
      count: Int?,
      barcodeValue: String? = null,
      barcodeName: String? = null,
    ): Mission? {
      val type = MissionType.from(typeKey) ?: return null
      if (type == MissionType.BARCODE && barcodeValue.isNullOrEmpty()) return null
      return Mission(
        type,
        difficulty,
        count ?: type.defaultCount,
        barcodeValue = barcodeValue.takeIf { type == MissionType.BARCODE },
        barcodeName = barcodeName?.trim()?.take(40).takeIf { type == MissionType.BARCODE },
      ).normalized()
    }

    fun fromJson(o: JSONObject): Mission? = create(
      o.optString("type"),
      o.optInt("difficulty", 1),
      if (o.has("count")) o.getInt("count") else null,
      if (o.has("barcodeValue")) o.getString("barcodeValue") else null,
      if (o.has("barcodeName")) o.getString("barcodeName") else null,
    )
  }
}
