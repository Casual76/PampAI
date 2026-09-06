package dev.pampa.pampai.core.assistant.reminders

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderRepeatTest {

  private val zone: ZoneId = ZoneId.of("Europe/Rome")
  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

  @Test
  fun parsesItalianWords() {
    assertEquals(ReminderRepeat.NONE, ReminderRepeat.parse(null))
    assertEquals(ReminderRepeat.DAILY, ReminderRepeat.parse("ogni giorno"))
    assertEquals(ReminderRepeat.WEEKDAYS, ReminderRepeat.parse("feriali"))
    assertEquals(ReminderRepeat.WEEKLY, ReminderRepeat.parse("ogni settimana"))
  }

  @Test
  fun nextOccurrenceKeepsTheTimeOfDay() {
    // Venerdi' 11 settembre 2026 alle 8: il giorno dopo e' sabato.
    val friday = at(2026, 9, 11, 8)
    assertEquals(at(2026, 9, 12, 8), ReminderRepeat.next(ReminderRepeat.DAILY, friday, friday, zone))
    // Nei feriali si salta il weekend: lunedi' 14.
    assertEquals(at(2026, 9, 14, 8), ReminderRepeat.next(ReminderRepeat.WEEKDAYS, friday, friday, zone))
    assertEquals(at(2026, 9, 18, 8), ReminderRepeat.next(ReminderRepeat.WEEKLY, friday, friday, zone))
    assertNull(ReminderRepeat.next(ReminderRepeat.NONE, friday, friday, zone))
  }

  @Test
  fun nextOccurrenceSkipsMissedOnes() {
    val monday = at(2026, 9, 7, 8)
    val later = at(2026, 9, 10, 12) // giovedi' a mezzogiorno: la prossima e' venerdi' alle 8
    assertEquals(at(2026, 9, 11, 8), ReminderRepeat.next(ReminderRepeat.DAILY, monday, later, zone))
  }
}
