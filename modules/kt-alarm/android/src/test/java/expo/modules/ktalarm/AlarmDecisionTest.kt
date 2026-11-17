package expo.modules.ktalarm

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM decisions only: these tests do not certify AlarmManager or Android lifecycle delivery. */
class AlarmDecisionTest {
  private fun alarm() = Alarm("fixture", 8, 30, "fixture", emptyList(), true, true, 5)

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance().apply {
      clear()
      set(year, month - 1, day, hour, minute)
    }.timeInMillis

  private fun inZone(id: String, block: () -> Unit) {
    val previous = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(id))
      block()
    } finally {
      TimeZone.setDefault(previous)
    }
  }

  @Test fun oneShotUsesTodayBeforeConfiguredMinute() = inZone("UTC") {
    assertEquals(at(2026, 11, 17, 8, 30), alarm().nextTrigger(at(2026, 11, 17, 8, 29)))
  }

  @Test fun equalMinuteRollsOneShotToTomorrow() = inZone("UTC") {
    assertEquals(at(2026, 11, 18, 8, 30), alarm().nextTrigger(at(2026, 11, 17, 8, 30)))
  }

  @Test fun futureSnoozeOverridesRepeatTime() = inZone("UTC") {
    val now = at(2026, 11, 17, 9, 0)
    assertEquals(now + 300_000, alarm().copy(days = listOf(1), snoozeUntil = now + 300_000).nextTrigger(now))
  }

  @Test fun expiredSnoozeReturnsToWallClockSchedule() = inZone("UTC") {
    val now = at(2026, 11, 17, 9, 0)
    assertEquals(at(2026, 11, 18, 8, 30), alarm().copy(snoozeUntil = now).nextTrigger(now))
  }

  @Test fun repeatUsesSundayZeroAndSkipsCurrentOccurrence() = inZone("UTC") {
    val sunday = alarm().copy(days = listOf(0))
    assertEquals(at(2026, 11, 22, 8, 30), sunday.nextTrigger(at(2026, 11, 17, 9, 0)))
    assertEquals(at(2026, 11, 29, 8, 30), sunday.nextTrigger(at(2026, 11, 22, 8, 30)))
  }

  @Test fun repeatKeepsLocalHourAcrossSpringDst() = inZone("America/New_York") {
    val now = at(2026, 3, 7, 8, 30)
    val next = alarm().copy(days = (0..6).toList()).nextTrigger(now)
    assertEquals(at(2026, 3, 8, 8, 30), next)
    assertEquals(23 * 3_600_000L, next - now)
  }

  @Test fun repeatKeepsLocalHourAcrossAutumnDst() = inZone("America/New_York") {
    val now = at(2026, 10, 31, 8, 30)
    val next = alarm().copy(days = (0..6).toList()).nextTrigger(now)
    assertEquals(at(2026, 11, 1, 8, 30), next)
    assertEquals(25 * 3_600_000L, next - now)
  }

  @Test fun snoozeLimitAndUnlimitedRemainDistinct() {
    assertFalse(alarm().copy(maxSnoozes = 0).canSnooze)
    assertFalse(alarm().copy(maxSnoozes = 2, snoozeCount = 2).canSnooze)
    assertEquals(0, alarm().copy(maxSnoozes = 2, snoozeCount = 3).snoozesLeft)
    assertTrue(alarm().copy(snoozeCount = 100).canSnooze)
    assertNull(alarm().snoozesLeft)
  }

  @Test fun barcodeRequiresRegisteredValueAndNormalizesCount() {
    assertNull(Mission.create("barcode", 1, 1))
    assertNull(Mission.create("barcode", 1, 1, ""))
    val mission = Mission.create("barcode", 99, 99, "fixture-code", "  fixture  ")!!
    assertEquals(1, mission.count)
    assertEquals(3, mission.difficulty)
    assertEquals("fixture-code", mission.barcodeValue)
    assertEquals("fixture", mission.barcodeName)
  }

  @Test fun unknownMissionIsRejectedAndOtherTypesDropBarcodeFields() {
    assertNull(Mission.create("future-type", 1, 1))
    val mission = Mission.create("walk", -1, -1, "fixture-code", "fixture")!!
    assertEquals(10, mission.count)
    assertEquals(0, mission.difficulty)
    assertNull(mission.barcodeValue)
    assertNull(mission.barcodeName)
  }
}
