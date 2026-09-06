package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.orchestrator.Conversation
import dev.antigravity.fluidengine.ai.orchestrator.HistoryCompactor
import dev.antigravity.fluidengine.ai.provider.ContentPart
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.provider.ReadyProvider
import dev.antigravity.fluidengine.ai.provider.ToolSpec

/** Quanto contesto vede il modello in questa domanda, e quanto ne regge. */
data class ContextEstimate(val tokens: Int, val window: Int) {
  val fraction: Float get() = (tokens.toFloat() / window).coerceIn(0f, 1f)
}

/**
 * Una stima, non un conto: il prompt di sistema, la storia compattata come la manda l'engine, gli
 * schemi dei tool caricati, gli allegati. Serve all'anello nella chat, che dice cosa il modello
 * *vede*, non quanto e' lunga la conversazione.
 */
object ContextMeter {

  fun estimate(systemPrompt: String, conversation: Conversation, historyBudget: Int, specs: List<ToolSpec>, attachments: List<ContentPart>, ready: ReadyProvider): ContextEstimate {
    val history = HistoryCompactor.compact(conversation, historyBudget)
    val tokens = HistoryCompactor.estimateTokens(systemPrompt) +
      HistoryCompactor.estimateTokens(history) +
      specs.sumOf { (it.description.length + it.parameters.toString().length + it.name.length) / 3 } +
      attachments.count { !it.isText } * 1_000 +
      attachments.filterIsInstance<ContentPart.Text>().sumOf { HistoryCompactor.estimateTokens(it.text) }
    return ContextEstimate(tokens, window(ready))
  }

  fun window(ready: ReadyProvider): Int {
    val model = ready.model(ModelTier.CHAT)
    return ready.catalogue?.chat(model)?.contextWindow ?: when (ready.provider.id) {
      ProviderId.GROQ -> 131_072
      ProviderId.GEMINI -> 1_048_576
      ProviderId.OPENROUTER -> 128_000
    }
  }
}
