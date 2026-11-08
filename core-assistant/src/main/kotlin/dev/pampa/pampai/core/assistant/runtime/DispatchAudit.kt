package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.pampai.core.assistant.db.DispatchAuditEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Each call keeps its immutable requested policy; provider callback includes its wire retries. */
internal fun ReadyProvider.audited(attempt: String, conversation: Long, message: Long, policy: ModelPolicy?,
  persist: suspend (DispatchAuditEntity) -> Unit): ReadyProvider {
  val inner = provider
  fun attach(request: ChatRequest) = request.copy(beforeDispatch = { wire ->
    check(policy == null || (wire.provider == policy.identity.provider && wire.model == policy.identity.model)) { "Il provider ha tentato di cambiare il modello scelto" }
    persist(DispatchAuditEntity(attemptId = attempt, conversationId = conversation, messageId = message,
      atMillis = System.currentTimeMillis(), provider = wire.provider.name, model = wire.model,
      requested = policy?.effort?.name ?: "AUTO", resolved = wire.resolved.name,
      effective = wire.effective, support = wire.support, adaptation = wire.adaptation))
    request.beforeDispatch(wire)
  })
  return copy(provider = object : ChatProvider by inner {
    override suspend fun complete(request: ChatRequest): ChatTurn = inner.complete(attach(request))
    override fun stream(request: ChatRequest): Flow<ChatDelta> = flow { inner.stream(attach(request)).collect { emit(it) } }
  })
}
