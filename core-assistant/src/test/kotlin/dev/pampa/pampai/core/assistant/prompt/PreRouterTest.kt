package dev.pampa.pampai.core.assistant.prompt

import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRouterTest {

  private val router = PreRouter()

  @Test
  fun `una domanda che nomina un gruppo solo lo decide senza chiamare nessuno`() {
    val alarm = router.decide("metti una sveglia alle 7", actionsEnabled = true)
    assertTrue(alarm.confident)
    assertEquals(setOf(PampaiGroup.OROLOGIO), alarm.groups)

    val calc = router.decide("quanto fa il 15% di 340?", actionsEnabled = true)
    assertTrue(calc.confident)
    assertEquals(setOf(PampaiGroup.CALCOLO), calc.groups)

    val memory = router.decide("ricordati che la mia fermata e' Dalmazia", actionsEnabled = true)
    assertTrue(memory.confident)
    assertEquals(setOf(PampaiGroup.ARIA), memory.groups)

    val music = router.decide("metti su un po' di musica", actionsEnabled = true)
    assertTrue(music.confident)
    assertEquals(setOf(PampaiGroup.RIPRODUZIONE), music.groups)
  }

  @Test
  fun `un'azione con le azioni spente non e' mai decisa da sola`() {
    val verdict = router.decide("accendi la torcia", actionsEnabled = false)
    assertFalse(verdict.confident)
    assertTrue(PampaiGroup.SISTEMA in verdict.groups)
  }

  @Test
  fun `piu' gruppi sfiorati vanno al router come suggerimento`() {
    val verdict = router.decide("cerca su wikipedia quanto fa la radice di 2", actionsEnabled = true)
    assertFalse(verdict.confident)
    assertTrue(verdict.groups.containsAll(setOf(PampaiGroup.WEB, PampaiGroup.CALCOLO)))
  }

  @Test
  fun `una chiacchiera non ha gruppi e non e' profonda`() {
    val verdict = router.decide("ciao, come stai?", actionsEnabled = true)
    assertTrue(verdict.groups.isEmpty())
    assertFalse(verdict.deep)
  }

  @Test
  fun `lo schermo e gli allegati chiedono il livello profondo`() {
    assertTrue(router.decide("cosa c'e' scritto sullo schermo?", actionsEnabled = true).deep)
    assertTrue(router.decide("che cos'e'?", actionsEnabled = true, hasAttachments = true).deep)
    assertTrue(router.decide("confronta tutti i provider meteo e dimmi quale conviene", actionsEnabled = true).deep)
  }
}
