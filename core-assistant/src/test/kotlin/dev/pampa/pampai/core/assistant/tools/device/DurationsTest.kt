package dev.pampa.pampai.core.assistant.tools.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationsTest {

  @Test
  fun parsesSpokenDurations() {
    assertEquals(600, Durations.parseSeconds("10 minuti"))
    assertEquals(5400, Durations.parseSeconds("1 ora e 30"))
    assertEquals(5400, Durations.parseSeconds("un'ora e mezza"))
    assertEquals(45, Durations.parseSeconds("45 secondi"))
    assertEquals(90, Durations.parseSeconds("1:30"))
    assertEquals(5400, Durations.parseSeconds("1:30:00"))
    assertEquals(1800, Durations.parseSeconds("mezz'ora"))
    assertEquals(300, Durations.parseSeconds("5"))
    assertNull(Durations.parseSeconds("presto"))
  }

  @Test
  fun labelsReadNaturally() {
    assertEquals("10 minuti", Durations.label(600))
    assertEquals("1 ora e 30 minuti", Durations.label(5400))
    assertEquals("1 minuto e 5 secondi", Durations.label(65))
  }
}
