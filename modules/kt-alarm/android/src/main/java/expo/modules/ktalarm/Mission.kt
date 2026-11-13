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
  TYPING("typing", 1, 5, 2);

  companion object {
    fun from(key: String?): MissionType? = values().firstOrNull { it.key == key }
  }
}

/**
 * @param difficulty only used by [MissionType.MATH]: 0 = easy … 3 = very hard.
 * @param count problems / shakes / phrases to complete.
 */
data class Mission(
  val type: MissionType,
  val difficulty: Int = 1,
  val count: Int = type.defaultCount,
) {
  fun normalized(): Mission = copy(
    difficulty = difficulty.coerceIn(0, MAX_DIFFICULTY),
    count = count.coerceIn(type.minCount, type.maxCount),
  )

  /** One line for the ringing screen and notification. */
  fun describe(): String = when (type) {
    MissionType.MATH -> if (count == 1) "Solve a math problem" else "Solve $count math problems"
    MissionType.SHAKE -> "Shake your phone $count times"
    MissionType.TYPING -> if (count == 1) "Type a phrase" else "Type $count phrases"
  }

  fun toJson(): JSONObject = JSONObject()
    .put("type", type.key)
    .put("difficulty", difficulty)
    .put("count", count)

  fun toMap(): Map<String, Any?> = mapOf(
    "type" to type.key,
    "difficulty" to difficulty,
    "count" to count,
  )

  companion object {
    const val MAX_DIFFICULTY = 3

    /** Unknown types (e.g. written by a newer app version) are dropped, not crashed on. */
    fun create(typeKey: String?, difficulty: Int, count: Int?): Mission? {
      val type = MissionType.from(typeKey) ?: return null
      return Mission(type, difficulty, count ?: type.defaultCount).normalized()
    }

    fun fromJson(o: JSONObject): Mission? =
      create(o.optString("type"), o.optInt("difficulty", 1), if (o.has("count")) o.getInt("count") else null)
  }
}
