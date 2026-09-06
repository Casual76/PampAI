package dev.pampa.pampai.core.assistant.tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DatesTest {

  private val today: LocalDate = LocalDate.of(2026, 9, 6) // domenica

  @Test
  fun `le date si leggono in ogni forma che il modello usa`() {
    assertEquals(today, Dates.parse("oggi", today))
    assertEquals(today.plusDays(1), Dates.parse("domani", today))
    assertEquals(LocalDate.of(2026, 9, 12), Dates.parse("2026-09-12", today))
    assertEquals(LocalDate.of(2026, 9, 12), Dates.parse("12/09/2026", today))
    assertEquals(LocalDate.of(2026, 9, 7), Dates.parse("lunedi'", today))
    assertEquals(LocalDate.of(2026, 9, 13), Dates.parse("domenica prossima", today))
    assertEquals(LocalDate.of(2026, 9, 4), Dates.parse("venerdi' scorso", today))
    assertNull(Dates.parse("boh", today))
  }

  @Test
  fun `le ore si leggono con e senza minuti, e con le parole`() {
    assertEquals(LocalTime.of(18, 30), Dates.parseTime("18:30"))
    assertEquals(LocalTime.of(18, 30), Dates.parseTime("18.30"))
    assertEquals(LocalTime.of(7, 0), Dates.parseTime("7"))
    assertEquals(LocalTime.of(6, 30), Dates.parseTime("6 e mezza"))
    assertEquals(LocalTime.of(12, 0), Dates.parseTime("mezzogiorno"))
    assertEquals(LocalTime.of(15, 0), Dates.parseTime("3 del pomeriggio"))
    assertNull(Dates.parseTime("presto"))
  }

  @Test
  fun `data e ora insieme, con il rinvio a domani se l'ora e' passata`() {
    val now = LocalDateTime.of(2026, 9, 6, 16, 40)
    assertEquals(LocalDateTime.of(2026, 9, 7, 18, 0), Dates.parseDateTime("domani alle 18", now))
    assertEquals(LocalDateTime.of(2026, 9, 6, 18, 0), Dates.parseDateTime("alle 18", now))
    assertEquals(LocalDateTime.of(2026, 9, 7, 7, 0), Dates.parseDateTime("alle 7", now))
    assertEquals(LocalDateTime.of(2026, 9, 12, 8, 30), Dates.parseDateTime("2026-09-12 08:30", now))
    assertEquals(LocalDateTime.of(2026, 9, 7, 9, 0), Dates.parseDateTime("lunedi'", now))
  }

  @Test
  fun `le etichette portano il giorno della settimana`() {
    assertEquals("2026-09-06 (dom)", Dates.label(today))
    assertEquals("2026-09-06 (dom) 16:40", Dates.label(LocalDateTime.of(2026, 9, 6, 16, 40)))
  }

  @Test
  fun `il testo si normalizza e si confronta per prefissi di parola`() {
    assertEquals("citta di castello", Text.normalize("Città di Castello!"))
    assertEquals(true, Text.matches("cast", "Città di Castello"))
    assertEquals(2, Text.score("bar scuola", "la circolare del bar della scuola"))
  }
}
