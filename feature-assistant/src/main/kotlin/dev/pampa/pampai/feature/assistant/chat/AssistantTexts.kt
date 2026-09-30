package dev.pampa.pampai.feature.assistant.chat

import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.orchestrator.ProviderSwitch
import dev.antigravity.fluidengine.ai.orchestrator.SwitchReason
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampai.core.assistant.prompt.AriaChips
import dev.pampa.pampai.core.assistant.service.AssistantNotifications

/** Da chiavi di stato, errori e chip alle parole: l'unico posto in cui la UI di Aria sceglie una frase. */
object AssistantTexts {

  /**
   * La frase di un fallimento.
   *
   * Per il limite di richieste nomina il servizio (dalla 2.8.0 lo porta lo stato `Failed`; chi
   * chiama puo' passarlo lo stesso per le righe salvate) e ricorda la riserva automatica: accesa
   * (il default) un limite arriva qui solo quando sono al limite tutti, spenta Aria aspetta e poi
   * si arrende, e chi legge "riprova fra 40 s" deve sapere che l'alternativa esiste.
   */
  fun failure(kind: FailureKind, retryAfterSec: Int? = null, provider: ProviderId? = null, tried: List<ProviderId> = emptyList()): String = when (kind) {
    FailureKind.NO_KEYS -> "Nessuna chiave verificata: aggiungine una nelle impostazioni."
    FailureKind.UNAUTHORIZED -> "La chiave non e' piu' valida: controllala nelle impostazioni."
    FailureKind.RATE_LIMITED -> {
      val who = provider?.label ?: "Il servizio"
      // La riserva ha gia' provato tutti: consigliarla sarebbe sbagliato, e nominarne uno solo pure.
      if (tried.size >= 2) "Sono al limite ${names(tried)}: riprova fra ${retryAfterSec?.let { "$it s" } ?: "poco"}."
      else if (retryAfterSec != null) "$who e' al limite: riprova fra $retryAfterSec s (o accendi la riserva automatica nelle impostazioni)."
      else "$who e' al limite di richieste: riprova fra poco (o accendi la riserva automatica nelle impostazioni)."
    }
    FailureKind.NETWORK -> "Niente rete: controlla la connessione e riprova."
    FailureKind.TIMEOUT -> "Ci ho messo troppo. Riprova, o dividi la domanda in pezzi piu' piccoli."
    FailureKind.BLOCKED -> "Il servizio ha rifiutato la richiesta per i suoi filtri: prova a riformularla, o a rigenerare con un altro servizio."
    FailureKind.PROVIDER -> "${provider?.label ?: "Il servizio"} ha risposto con un errore: riprova, o rigenera con un altro servizio."
    FailureKind.MODEL_UNAVAILABLE -> "Il modello scelto per ${provider?.label ?: "questo servizio"} non c'e' piu': ne ho scelto un altro, riprova."
    FailureKind.CONTEXT_TOO_LONG -> "La conversazione e' troppo lunga per ${provider?.label ?: "questo servizio"}: aprine una nuova, o rigenera con un altro servizio."
    FailureKind.MICROPHONE -> "Il microfono non e' disponibile: chiudi l'app che lo sta usando."
    FailureKind.TRANSCRIPTION -> "Non sono riuscita a trascrivere: riprova."
    FailureKind.UNKNOWN -> "Qualcosa e' andato storto."
  }

  /**
   * Il fallimento in due o tre parole, per il titolo di una riga del foglio "Dettagli": la frase
   * intera ([failure]) sta gia' sopra, e li' serve il nome della cosa, non il consiglio.
   */
  fun failureTitle(kind: FailureKind): String = when (kind) {
    FailureKind.NO_KEYS -> "Nessuna chiave"
    FailureKind.UNAUTHORIZED -> "Chiave non valida"
    FailureKind.RATE_LIMITED -> "Limite di richieste"
    FailureKind.NETWORK -> "Niente rete"
    FailureKind.TIMEOUT -> "Tempo scaduto"
    FailureKind.BLOCKED -> "Bloccata dai filtri"
    FailureKind.PROVIDER -> "Errore del servizio"
    FailureKind.MODEL_UNAVAILABLE -> "Modello non disponibile"
    FailureKind.CONTEXT_TOO_LONG -> "Conversazione troppo lunga"
    FailureKind.MICROPHONE -> "Microfono occupato"
    FailureKind.TRANSCRIPTION -> "Trascrizione non riuscita"
    FailureKind.UNKNOWN -> "Errore sconosciuto"
  }

  /** La riga di stato; [provider] e' chi stava rispondendo, per nominarlo in un fallimento per limite. */
  fun statusLine(state: AssistantState, provider: ProviderId? = null): String? = when (state) {
    is AssistantState.Listening -> "Ti ascolto…"
    AssistantState.Transcribing -> "Trascrivo…"
    is AssistantState.Classifying -> "Capisco cosa serve…"
    is AssistantState.Working -> {
      val base = AssistantNotifications.statusFor(state.statusKey, state.tier)
      if (state.statusExtra > 0) "$base (+${state.statusExtra})" else base
    }
    is AssistantState.WaitingRateLimit -> "${state.provider.label} e' al limite: riprovo fra ${state.secondsLeft} s"
    is AssistantState.SwitchingProvider -> state.reason?.let { "${state.from.label} ${reasonText(it)}: passo a ${state.to.label}…" } ?: "Passo a ${state.to.label}…"
    is AssistantState.Answering -> "Rispondo…"
    is AssistantState.AwaitingConfirmation -> "Serve una conferma"
    AssistantState.HeardNothing -> "Non ho sentito niente"
    is AssistantState.Failed -> failure(state.kind, state.retryAfterSec, state.provider ?: provider)
    is AssistantState.Cancelled -> "Fermata"
    is AssistantState.Done -> null
    AssistantState.Idle -> null
  }

  /**
   * Perche' un servizio ha passato la mano, detto di lui: "Groq era al limite". Al passato perche'
   * si legge sotto una risposta gia' arrivata, o in una riga di stato che sta gia' cambiando.
   */
  fun reasonText(reason: SwitchReason): String = when (reason) {
    SwitchReason.RATE_LIMITED -> "era al limite"
    SwitchReason.SERVER -> "non era disponibile"
    SwitchReason.TIMEOUT -> "non rispondeva"
    SwitchReason.NETWORK -> "non era raggiungibile"
    SwitchReason.TOOL_USE_FAILED -> "ha sbagliato a usare uno strumento"
    SwitchReason.MODEL_UNAVAILABLE -> "non ha piu' il modello scelto"
    SwitchReason.CONTEXT_TOO_LONG -> "non reggeva una conversazione cosi' lunga"
    SwitchReason.BAD_REQUEST -> "ha rifiutato la richiesta"
    SwitchReason.EMPTY_ANSWER -> "ha dato una risposta vuota"
    SwitchReason.PARSE -> "ha risposto in modo illeggibile"
  }

  /** I servizi per cui una domanda e' passata, in ordine e senza doppioni, dai suoi cambi. */
  fun tried(switches: List<ProviderSwitch>): List<ProviderId> =
    if (switches.isEmpty()) emptyList() else (listOf(switches.first().from) + switches.map { it.to }).distinct()

  /** "Groq", "Groq e Gemini", "Groq, Gemini e OpenRouter". */
  private fun names(providers: List<ProviderId>): String {
    val labels = providers.map { it.label }
    return if (labels.size <= 1) labels.joinToString() else labels.dropLast(1).joinToString(", ") + " e " + labels.last()
  }

  /**
   * La riga sotto una risposta arrivata da un servizio diverso da quello di partenza: "Ha risposto
   * Gemini: Groq era al limite". La riserva automatica e' accesa di default, e un cambio di servizio
   * che non si dice e' un altro modello che risponde al posto di quello scelto senza che si sappia.
   * Null senza cambi.
   */
  fun switchLine(switches: List<ProviderSwitch>): String? {
    val last = switches.lastOrNull() ?: return null
    val why = switches.distinctBy { it.from }.joinToString(", ") { "${it.from.label} ${reasonText(it.reason)}" }
    return "Ha risposto ${last.to.label}: $why"
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
