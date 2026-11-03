package dev.pampa.pampai.feature.assistant.chat

import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.aiui.overlay.FluidFailureReport
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.Run

/**
 * Il resoconto di una risposta finita male, per il foglio «Dettagli» dell'engine
 * ([dev.antigravity.fluidengine.aiui.overlay.FluidFailureDetailsSheet]): dalla riga del run su disco
 * se c'e' (servizio, modello, codice HTTP, cosa ha detto il servizio, i servizi per cui e' passata) e
 * dallo stato `Failed` del runtime per il resto, nel mezzo secondo prima che la riga arrivi.
 *
 * Il resoconto in se' e' dell'engine; questo e' il pezzo che sa cos'e' un [Message] e un [Run] di Room.
 */
internal fun failureReportOf(message: Message, run: Run?, live: AssistantState?): FluidFailureReport {
  val failed = live as? AssistantState.Failed
  val detail = run?.failure
  val provider = detail?.provider ?: failed?.provider ?: run?.models?.lastOrNull()?.provider ?: run?.provider
  return FluidFailureReport(
    kind = message.failureKind ?: failed?.kind,
    provider = provider,
    model = run?.models?.lastOrNull { it.provider == provider }?.model ?: run?.chatModel,
    reason = detail?.reason ?: failed?.reason,
    httpCode = detail?.httpCode ?: failed?.error?.httpCode,
    providerMessage = detail?.message ?: failed?.error?.providerMessage,
    switches = run?.switches.orEmpty(),
    technical = run?.error,
  )
}
