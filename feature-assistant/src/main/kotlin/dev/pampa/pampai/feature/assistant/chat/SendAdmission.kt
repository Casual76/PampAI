package dev.pampa.pampai.feature.assistant.chat

import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.pampa.pampai.core.assistant.db.Anchor
import dev.pampa.pampai.core.assistant.runtime.AssistantRequest
import dev.pampa.pampai.core.assistant.tools.Surface

/** The production send admission boundary: only the shared preflight decides image eligibility.
 * The identical request is retained through admission, queueing and dispatch. */
internal class SendAdmission(private val preflight: suspend (AssistantRequest) -> Unit) {
  suspend fun admit(request: AssistantRequest, stillOwned: () -> Boolean): Boolean {
    preflight(request)
    return stillOwned()
  }

  suspend fun dispatch(request: AssistantRequest, stillOwned: () -> Boolean, submit: () -> Boolean): Boolean =
    admit(request, stillOwned) && submit()
}

internal fun QueuedQuestion.asRequest(): AssistantRequest = request ?: AssistantRequest(
  conversationId, text, AskMode.TEXT, attachments, Surface.APP,
  anchor = editOf?.let { Anchor.Edit(it.id) } ?: Anchor.Continue,
  plugin = selectedPlugin, deep = deep, temporary = temporary, modelPolicy = policy,
)
