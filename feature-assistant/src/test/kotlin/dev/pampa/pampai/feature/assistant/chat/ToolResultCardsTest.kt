package dev.pampa.pampai.feature.assistant.chat

import dev.antigravity.fluidengine.ai.tools.ToolOutcome
import org.junit.Assert.*
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.receipts.ToolReceipt
import kotlinx.serialization.json.*
import org.junit.Test

class ToolResultCardsTest {
  @Test fun `every outcome has a distinct honest label`() {
    assertEquals(5, ToolOutcome.entries.map(::receiptOutcomeLabel).distinct().size)
    assertEquals("Richiesta inviata", receiptOutcomeLabel(ToolOutcome.ACCEPTED))
    assertEquals("Verificato", receiptOutcomeLabel(ToolOutcome.COMPLETED))
    assertTrue(receiptOutcomeLabel(ToolOutcome.UNCERTAIN).contains("nessuna ripetizione automatica"))
  }

  @Test fun `signed Spotify data renders names and unavailable entries without promoting uncertain write`() {
    val result = ToolResultDetails(ToolOutcome.UNCERTAIN,
      entities = listOf(ToolEntityRef("spotify", "playlist", "private-id", "spotify:playlist:private-id")),
      sources = listOf(ToolSourceRef("spotify", account = "private-account")),
      data = Json.parseToJsonElement("""{"playlist":{"name":"La mia playlist","id":"private-id"},"items":[{"position":0,"item":{"track":{"name":"Brano reale","is_playable":true}}},{"position":1,"item":{"track":null}},{"position":2,"item":{"track":{"name":"Rimosso","is_playable":false}}}]}""").jsonObject)
    val receipt = ToolReceipt("operation", "playlist_crea", "", result, 0)
    assertEquals("La mia playlist", receiptTitle(receipt))
    assertEquals(listOf("Brano reale", "Brano non disponibile", "Rimosso (non disponibile)"), receiptTrackLabels(receipt))
    assertTrue(receiptCanOpen(receipt))
    assertTrue(receiptOutcomeLabel(receipt.result.outcome).contains("nessuna ripetizione automatica"))
    assertFalse((receiptTrackLabels(receipt) + receiptTitle(receipt)).joinToString().contains("private-id"))
  }
  @Test fun `local MediaSession receipt keeps its flat fields and fallback has no raw ID`() {
    val receipt = ToolReceipt("operation", "musica_stato", "", ToolResultDetails(ToolOutcome.COMPLETED,
      entities = listOf(ToolEntityRef("fluidify", "playlist", "raw-media-id")),
      data = Json.parseToJsonElement("""{"title":"Locale","items":[{"title":"Titolo locale"}]}""").jsonObject), 0)
    assertEquals("Locale", receiptTitle(receipt))
    assertEquals(listOf("Titolo locale"), receiptTrackLabels(receipt))
    assertTrue(receiptCanOpen(receipt))
    assertEquals("Playlist", receiptTitle(receipt.copy(result = receipt.result.copy(data = null))))
  }
}
