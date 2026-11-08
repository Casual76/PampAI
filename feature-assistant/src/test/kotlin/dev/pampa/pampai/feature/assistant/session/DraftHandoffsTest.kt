package dev.pampa.pampai.feature.assistant.session

import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.runtime.*
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.provider.ProviderId
import org.junit.Assert.*
import org.junit.Test

class DraftHandoffsTest {
  @Test fun `draft and owned pixels plus exact policy travel once to intended conversation`() {
    val bytes = byteArrayOf(1, 2, 3)
    val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "text"), ThinkingLevel.HIGH, ExactModel(ProviderId.GEMINI, "vision"))
    val token = DraftHandoffs.put(DraftHandoff(42, "Non inviare", listOf(PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", "schermo.jpg", bytes)), policy))
    assertTrue(DraftHandoffs.canClaim(token, 42))
    assertFalse(DraftHandoffs.canClaim(token, 43))
    bytes.fill(0) // source screenshot can be cleared onHide
    assertNull(DraftHandoffs.consume(token, 43))
    val received = requireNotNull(DraftHandoffs.consume(token, 42))
    assertEquals("Non inviare", received.text)
    assertArrayEquals(byteArrayOf(1, 2, 3), received.attachments.single().bytes)
    assertEquals(policy, received.policy)
    assertFalse(DraftHandoffs.canClaim(token, 42)) // recreated Activity ignores the stale Intent
    assertNull(DraftHandoffs.consume(token, 42))
    assertNull(DraftHandoffs.consume(token, null))
  }
  @Test fun `new overlay targets new draft even when app already has a chat`() {
    val token = DraftHandoffs.put(DraftHandoff(null, "", emptyList(), null))
    assertNull(DraftHandoffs.consume(token, 7))
    assertNotNull(DraftHandoffs.consume(token, null))
    assertNull(DraftHandoffs.consume(token, null))
  }
  @Test fun `cancelled launch and superseded token never leak to next chat`() {
    val cancelled = DraftHandoffs.put(DraftHandoff(null, "private", emptyList(), null))
    DraftHandoffs.discard(cancelled)
    assertNull(DraftHandoffs.consume(cancelled, null))
    val old = DraftHandoffs.put(DraftHandoff(null, "old", emptyList(), null))
    val latest = DraftHandoffs.put(DraftHandoff(5, "latest", emptyList(), null))
    assertNull(DraftHandoffs.consume(old, null))
    assertEquals("latest", DraftHandoffs.consume(latest, 5)?.text)
  }
}
