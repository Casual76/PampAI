package dev.pampa.pampai.core.assistant.runtime

import android.util.Log
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiKeyVerifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * La manutenzione dei modelli all'avvio dell'app: per ogni servizio con la chiave verificata il
 * catalogo si rinfresca se ha piu' di un giorno, e le scelte si riallineano al catalogo
 * (`AiKeyVerifier.refreshIfStale`, che chiama anche `reconcile` sul catalogo che c'e').
 *
 * Prima succedeva solo aprendo le impostazioni: un modello ritirato o sconsigliato restava scelto
 * finche' l'utente non ci passava, e intanto ogni domanda falliva o cadeva sulla riserva. La rete
 * si tocca al massimo una volta al giorno per servizio (lo decide `refreshIfStale`); qui si evita
 * solo di rifare il giro due volte nello stesso processo.
 */
@Singleton
class ModelsMaintenance @Inject constructor(
  private val keys: AiKeyStore,
  private val verifier: AiKeyVerifier,
) {

  @Volatile private var lastRunAt = 0L

  /** Da chiamare all'avvio, fuori dal main thread: non lancia mai. */
  suspend fun runDaily(now: Long = System.currentTimeMillis()) {
    if (lastRunAt != 0L && now - lastRunAt < AiKeyVerifier.DAY_MILLIS) return
    lastRunAt = now
    val verified = runCatching { keys.currentStates().filterValues { it.verified }.keys }.getOrDefault(emptySet())
    coroutineScope {
      verified.forEach { provider ->
        launch {
          try {
            verifier.refreshIfStale(provider)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            Log.w(TAG, "manutenzione dei modelli di ${provider.id} non riuscita", e)
          }
        }
      }
    }
  }

  private companion object {
    const val TAG = "ModelsMaintenance"
  }
}
