package dev.pampa.pampai.core.assistant.chat

import dev.pampa.pampai.core.assistant.chat.Greetings.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingsTest {

  @Test
  fun ogniOraDelGiornoCadeInUnaFascia() {
    val bands = (0..23).map { Greetings.band(it) }
    assertEquals(24, bands.size)
    assertEquals(Band.NOTTE, Greetings.band(0))
    assertEquals(Band.NOTTE, Greetings.band(4))
    assertEquals(Band.MATTINA, Greetings.band(5))
    assertEquals(Band.MATTINA, Greetings.band(11))
    assertEquals(Band.MEZZOGIORNO, Greetings.band(12))
    assertEquals(Band.POMERIGGIO, Greetings.band(14))
    assertEquals(Band.POMERIGGIO, Greetings.band(18))
    assertEquals(Band.SERA, Greetings.band(19))
    assertEquals(Band.SERA, Greetings.band(22))
    assertEquals(Band.NOTTE, Greetings.band(23))
  }

  @Test
  fun ogniFasciaHaPiuDiUnaFrase() {
    Band.entries.forEach { band ->
      val lines = Greetings.lines(band)
      assertTrue("$band ha una sola frase", lines.size >= 3)
      assertEquals("$band ha frasi ripetute", lines.size, lines.toSet().size)
      assertTrue("$band ha una frase vuota", lines.none { it.isBlank() })
    }
  }

  @Test
  fun laSceltaGiraSuTutteLeFrasiEnonEsceMaiDallaFascia() {
    val mattina = Greetings.lines(Band.MATTINA)
    val viste = (0L until 40L).map { Greetings.greeting(9, it) }.toSet()
    assertEquals(mattina.toSet(), viste)
  }

  @Test
  fun unSemeNegativoNonFaEsplodereNiente() {
    assertTrue(Greetings.greeting(21, -7L) in Greetings.lines(Band.SERA))
    assertTrue(Greetings.greeting(2, Long.MIN_VALUE) in Greetings.lines(Band.NOTTE))
  }
}
