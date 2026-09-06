package dev.pampa.pampai.core.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTextTest {

  @Test
  fun stripsInlineMarkdownAndChips() {
    assertEquals("Domani piove a Firenze, porta l'ombrello.", PlainText.of("Domani **piove** a *Firenze*, porta `l'ombrello`. [[apri:meteo]]"))
    assertEquals("Vedi il sito", PlainText.of("Vedi [il sito](https://esempio.it)"))
  }

  @Test
  fun flattensHeadingsListsAndTables() {
    val markdown = """
      ## Voti
      - Matematica: 8
      1. Storia: 7
      | Materia | Voto |
      |---|---|
      | Fisica | 9 |
    """.trimIndent()
    assertEquals("Voti\nMatematica: 8\nStoria: 7\nMateria, Voto\nFisica, 9", PlainText.of(markdown))
  }

  @Test
  fun keepsCodeBlockContentWithoutFences() {
    val plain = PlainText.of("Ecco:\n```kotlin\nval x = 1\n```\nFine.")
    assertEquals("Ecco:\nval x = 1\nFine.", plain)
  }

  @Test
  fun newSentencesReturnsOnlyClosedOnesWhileStreaming() {
    val (first, consumed) = PlainText.newSentences("Ciao Alessio. Domani piove", spokenChars = 0, final = false)
    assertEquals(listOf("Ciao Alessio."), first)
    val (second, consumed2) = PlainText.newSentences("Ciao Alessio. Domani piove a Firenze! Porta", spokenChars = consumed, final = false)
    assertEquals(listOf("Domani piove a Firenze!"), second)
    val (tail, end) = PlainText.newSentences("Ciao Alessio. Domani piove a Firenze! Porta l'ombrello", spokenChars = consumed2, final = true)
    assertEquals(listOf("Porta l'ombrello"), tail)
    assertEquals(PlainText.of("Ciao Alessio. Domani piove a Firenze! Porta l'ombrello").length, end)
  }

  @Test
  fun newSentencesSplitsOnLineBreaksAndSkipsBlank() {
    val (sentences, _) = PlainText.newSentences("- Uno\n- Due\n\n", spokenChars = 0, final = true)
    assertEquals(listOf("Uno", "Due"), sentences)
    assertTrue(PlainText.newSentences("Ciao.", spokenChars = 5, final = true).first.isEmpty())
  }
}
