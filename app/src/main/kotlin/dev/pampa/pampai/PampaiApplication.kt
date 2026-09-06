package dev.pampa.pampai

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.EngineCompatibility
import dev.antigravity.fluidengine.foundation.EngineFlag
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** I flag remoti, dichiarati con il valore con cui la build e' stata provata. */
object Flags {
  /** La sessione di sistema (tasto di accensione). */
  val AssistantSession = EngineFlag(key = "assistant_session", default = true)

  /** I tool delle app collegate attraverso il bridge. */
  val FederatedTools = EngineFlag(key = "federated_tools", default = true)

  /** La voce cloud per leggere le risposte. */
  val CloudTts = EngineFlag(key = "cloud_tts", default = true)

  /** La ricerca web attraverso il provider. */
  val WebSearch = EngineFlag(key = "web_search", default = true)
}

@HiltAndroidApp
class PampaiApplication : Application() {

  @Inject lateinit var remoteConfig: EngineRemoteConfig

  /** Vive quanto il processo: niente di quello che parte qui ha qualcosa da cui essere cancellato. */
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()

    // Il file di controllo, se la copia in cache e' vecchia. Non blocca niente: finche' non
    // arriva, l'app usa l'ultima risposta valida (o i default compilati).
    applicationScope.launch {
      runCatching { remoteConfig.refreshIfStale() }
    }

    // Cosa fare se questa build e' rimasta indietro: per ora lo si scrive nel log; la UI lo
    // mostrera' nelle impostazioni, e il kill switch ferma le domande.
    applicationScope.launch {
      runCatching {
        when (remoteConfig.compatibility()) {
          EngineCompatibility.OK -> Unit
          EngineCompatibility.UPDATE_RECOMMENDED -> Log.i(TAG, "engine: aggiornamento consigliato")
          EngineCompatibility.UPDATE_REQUIRED -> Log.w(TAG, "engine: aggiornamento necessario")
        }
      }
    }
  }

  private companion object {
    const val TAG = "PampAI"
  }
}
