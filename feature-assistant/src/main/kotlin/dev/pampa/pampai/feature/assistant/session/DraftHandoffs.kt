package dev.pampa.pampai.feature.assistant.session

import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.runtime.ModelPolicy
import java.util.UUID

/** Process-owned transfer: survives session hide/destruction and Activity recreation, never persisted
 * (in particular temporary conversations). Only an explicit Intent token can claim a draft.
 * Bytes are owned here; clearing ScreenContextStore cannot invalidate them. */
data class DraftHandoff(val conversationId: Long?, val text: String,
  val attachments: List<PendingAttachment>, val policy: ModelPolicy?)

object DraftHandoffs {
  const val SETTINGS_EXTRA = "dev.pampa.pampai.extra.SETTINGS_SECTION"
  const val EXTRA = "dev.pampa.pampai.extra.DRAFT_HANDOFF"
  private val pending = mutableMapOf<String, DraftHandoff>()
  @Synchronized fun put(value: DraftHandoff): String {
    val token = UUID.randomUUID().toString()
    // Only the latest explicit expansion may be delivered. Closing alone never creates a token.
    pending.clear()
    pending[token] = value.copy(attachments = value.attachments.map {
      PendingAttachment(it.kind, it.mime, it.name, it.bytes.copyOf())
    })
    return token
  }
  @Synchronized fun canClaim(token: String, conversationId: Long?): Boolean = pending[token]?.conversationId == conversationId && pending.containsKey(token)

  @Synchronized fun consume(token: String, conversationId: Long?): DraftHandoff? {
    val value = pending[token] ?: return null
    if (value.conversationId != conversationId) return null
    pending.remove(token)
    return value
  }
  @Synchronized fun discard(token: String) { pending.remove(token) }
}
