package dev.pampa.pampai.feature.assistant.chat

import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.pampa.pampai.core.assistant.prompt.AriaChips
import dev.pampa.pampai.core.assistant.service.AssistantNotifications

/** Da chiavi di stato, errori e chip alle parole: l'unico posto in cui la UI di Aria sceglie una frase. */
object AssistantTexts {

  fun failure(kind: FailureKind, retryAfterSec: Int? = null): String = when (kind) {
    FailureKind.NO_KEYS -> "Nessuna chiave verificata: aggiungine una nelle impostazioni."
    FailureKind.UNAUTHORIZED -> "La chiave non e' piu' valida: controllala nelle impostazioni."
    FailureKind.RATE_LIMITED -> if (retryAfterSec != null) "Il servizio e' al limite: riprova fra $retryAfterSec s." else "Il servizio e' al limite di richieste: riprova fra poco."
    FailureKind.NETWORK -> "Niente rete."
    FailureKind.TIMEOUT -> "Ci ho messo troppo: riprova con una domanda piu' semplice."
    FailureKind.BLOCKED -> "Il servizio ha rifiutato la richiesta."
    FailureKind.PROVIDER -> "Il servizio ha risposto con un errore."
    FailureKind.MICROPHONE -> "Il microfono non e' disponibile: chiudi l'app che lo sta usando."
    FailureKind.TRANSCRIPTION -> "Non sono riuscita a trascrivere: riprova."
    FailureKind.UNKNOWN -> "Qualcosa e' andato storto."
  }

  fun statusLine(state: AssistantState): String? = when (state) {
    is AssistantState.Listening -> "Ti ascolto…"
    AssistantState.Transcribing -> "Trascrivo…"
    is AssistantState.Classifying -> "Capisco cosa serve…"
    is AssistantState.Working -> {
      val base = AssistantNotifications.statusFor(state.statusKey, state.tier)
      if (state.statusExtra > 0) "$base (+${state.statusExtra})" else base
    }
    is AssistantState.WaitingRateLimit -> "${state.provider.label} e' al limite: riprovo fra ${state.secondsLeft} s"
    is AssistantState.SwitchingProvider -> "Passo a ${state.to.label}…"
    is AssistantState.Answering -> "Rispondo…"
    is AssistantState.AwaitingConfirmation -> "Serve una conferma"
    AssistantState.HeardNothing -> "Non ho sentito niente"
    is AssistantState.Failed -> failure(state.kind, state.retryAfterSec)
    is AssistantState.Cancelled -> "Fermata"
    is AssistantState.Done -> null
    AssistantState.Idle -> null
  }

  fun chipLabel(chip: AnswerChip): String = when (chip.id) {
    AriaChips.APP -> "Apri ${chip.value}"
    AriaChips.URL -> chip.value?.removePrefix("https://")?.removePrefix("http://")?.take(40) ?: "Apri il link"
    AriaChips.CONVERSATION -> "Apri la conversazione"
    AriaChips.PLACE -> chip.value ?: "Luogo"
    AriaChips.SETTINGS -> "Impostazioni" + (chip.value?.let { " · $it" } ?: "")
    AriaChips.REMINDER -> "Promemoria"
    else -> chip.value ?: chip.id
  }
}
