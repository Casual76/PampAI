package dev.pampa.pampai.core.assistant.tools

import android.content.Context
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.ConfirmationOutcome
import dev.antigravity.fluidengine.ai.provider.ModelCapabilities
import dev.antigravity.fluidengine.ai.provider.ReadyProvider
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.db.MemoryRepository
import dev.pampa.pampai.core.assistant.runtime.PampaiConfirmationGate
import dev.pampa.pampai.core.assistant.screen.ScreenContextStore
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** Da dove e' partita la domanda: la chat dell'app, o l'overlay di sistema sopra un'altra app. */
enum class Surface { APP, SESSION }

/** Il tool che la domanda ha fatto partire deve dire dove sta girando: il suo nome per il cancello. */
const val ACTIONS_OFF = "le azioni sono disattivate nelle impostazioni di PampAI: dillo all'utente"

/**
 * Cosa un tool puo' toccare mentre gira: il telefono, l'ora, la memoria, le conversazioni, le
 * impostazioni, il cancello delle conferme, il provider in uso (per le sotto-chiamate, come la
 * ricerca web). Costruito per ogni domanda dall'engine di PampAI. Le parti che arrivano con le
 * fasi dopo (schermo, musica, app collegate) si aggiungono qui, con un default nullo.
 */
class PampaiToolContext(
  val app: Context,
  val zone: ZoneId,
  val locale: Locale,
  val now: () -> Long,
  val surface: Surface,
  val mode: AskMode,
  val actionsEnabled: Boolean,
  val gate: PampaiConfirmationGate,
  val memory: MemoryRepository,
  val conversations: ConversationsRepository,
  val settings: PampaiSettingsStore,
  val http: AiHttp,
  /** Il provider che sta rispondendo: i tool che fanno una chiamata a parte (ricerca web) usano lui. */
  val provider: ReadyProvider?,
  /** Cosa il modello profondo del provider in uso sa prendere: decide la forma di un allegato. */
  val deepCapabilities: ModelCapabilities,
  val conversationId: Long,
  val language: String = "it",
  /** Le righe su cosa sa fare Aria, per il tool `aiuto`: le scrive chi costruisce il registry. */
  val capabilitiesSummary: () -> String = { "" },
  /** Lo schermo sotto la sessione di sistema (screenshot, testo, app in primo piano); null nella chat dell'app. */
  val screen: ScreenContextStore? = null,
) {
  private val traceList = java.util.Collections.synchronizedList(mutableListOf<PampaiToolTrace>())

  /** Le chiamate agli strumenti di questa domanda, nell'ordine in cui sono finite. */
  val traces: List<PampaiToolTrace> get() = synchronized(traceList) { traceList.toList() }

  fun trace(trace: PampaiToolTrace) {
    traceList += trace
  }

  val today: LocalDate get() = LocalDate.now(zone)

  val nowDateTime: LocalDateTime get() = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now()), zone)

  /**
   * La conferma di un'azione, passando dalle azioni fidate: torna null se si puo' procedere, o il
   * testo da restituire al modello se l'utente ha detto no (o non ha risposto).
   */
  suspend fun confirm(tool: String, title: String, detail: String?): ToolOutput? {
    if (!actionsEnabled) return ToolOutput(ACTIONS_OFF)
    return when (val outcome = gate.ask(tool, title, detail)) {
      ConfirmationOutcome.CONFIRMED -> null
      else -> ToolOutput(PampaiConfirmationGate.outcomeText(outcome))
    }
  }
}
