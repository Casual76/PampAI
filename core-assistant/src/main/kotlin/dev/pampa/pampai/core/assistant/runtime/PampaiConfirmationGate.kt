package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.orchestrator.AiConfirmationGate
import dev.antigravity.fluidengine.ai.orchestrator.ConfirmationOutcome
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * Il cancello delle conferme di Aria: quello dell'engine, piu' le **azioni fidate** che l'utente ha
 * scelto nelle impostazioni (un timer, la torcia) e che non chiedono piu'. Alcune non si possono
 * fidare mai: chiamate, installazioni, cancellazioni, rimozioni, prenotazioni.
 */
@Singleton
class PampaiConfirmationGate @Inject constructor(
  private val gate: AiConfirmationGate,
  private val settings: PampaiSettingsStore,
) {

  val current: StateFlow<PendingConfirmation?> = gate.current

  /**
   * Chiede conferma per [tool], a meno che sia fidato. Torna l'esito come lo vuole il testo del
   * tool: chi chiama scrive "fatto" / "l'utente ha annullato" / "nessuna conferma".
   */
  suspend fun ask(tool: String, title: String, detail: String?): ConfirmationOutcome {
    if (canTrust(tool) && settings.current().trustedActions.contains(tool)) return ConfirmationOutcome.CONFIRMED
    return gate.ask(title, detail)
  }

  fun resolve(id: Long, confirmed: Boolean) = gate.resolve(id, confirmed)

  fun cancel() = gate.cancel()

  companion object {
    /** I tool che non si possono mai mettere fra le azioni fidate. */
    val NEVER_TRUSTED: Set<String> = setOf("chiama", "store_installa", "store_aggiorna_tutto", "evento_elimina", "dimentica")

    fun canTrust(tool: String): Boolean =
      tool !in NEVER_TRUSTED && !tool.endsWith("_elimina") && !tool.endsWith("_rimuovi") && !tool.endsWith("_installa")

    /** Il testo che torna al modello per un esito: le stesse frasi in tutta l'app. */
    fun outcomeText(outcome: ConfirmationOutcome, done: String = "fatto"): String = when (outcome) {
      ConfirmationOutcome.CONFIRMED -> done
      ConfirmationOutcome.REJECTED -> "l'utente ha annullato: non fatto"
      ConfirmationOutcome.TIMEOUT -> "nessuna conferma dall'utente: non fatto"
    }
  }
}
