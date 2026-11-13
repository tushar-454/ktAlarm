package expo.modules.ktalarm

import java.util.concurrent.CopyOnWriteArraySet

/** In-process event bus between the service, the ringing activity and the JS module. */
object AlarmEvents {
  fun interface Listener {
    /** [ringingAlarmId] is null when nothing is ringing. */
    fun onEvent(type: Type, ringingAlarmId: String?)
  }

  enum class Type { ALARMS_CHANGED, RINGING_CHANGED }

  private val listeners = CopyOnWriteArraySet<Listener>()

  fun add(listener: Listener) = listeners.add(listener)
  fun remove(listener: Listener) = listeners.remove(listener)

  fun emit(type: Type) {
    val ringing = AlarmService.activeAlarmId
    listeners.forEach { it.onEvent(type, ringing) }
  }
}
